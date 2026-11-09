#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
KUN-DB 交互式测试客户端（MySQL 线协议）

用途：服务端（KunDbServer，默认监听 8020）跑起来后，用它连上并逐条执行 SQL，
     直观验证返回。无需等 Qt 客户端即可完成「协议层 / 引擎层」的端到端测试。

原理：KUN-DB 自 v2 起对外是标准 MySQL 线协议，本客户端即一个最小 MySQL 客户端：
      1) 连上后收 Handshake v10；
      2) 回 HandshakeResponse41（mysql_native_password 挑战应答）；
      3) 收 OK 即登录成功；
      4) 每行 SQL 用 COM_QUERY(0x03) 发出，解析 OK/ERR/结果集；
      5) "ping" 发 COM_PING(0x0E) 探活。

用法：
    python test_client.py                              # 连 127.0.0.1:8020，账号 root/123456
    python test_client.py <host> <port>                # 指定地址
    python test_client.py <host> <port> <user> <pwd>   # 指定账号

连上后逐行输入 SQL（以回车提交），输入 exit / quit 退出。
特殊命令：ping -> 发心跳并收 OK 包。
"""
import hashlib
import socket
import struct
import sys
import time

# ---- MySQL 线协议能力位（HandshakeResponse 用）----
CLIENT_LONG_PASSWORD = 0x00000001
CLIENT_PROTOCOL_41 = 0x00000200
CLIENT_SECURE_CONNECTION = 0x00008000
CLIENT_PLUGIN_AUTH = 0x00080000

DEFAULT_HOST, DEFAULT_PORT, DEFAULT_USER, DEFAULT_PWD = "127.0.0.1", 8020, "root", "123456"


# ============ 底层收发 ============
def recv_exact(sock, n):
    buf = b""
    while len(buf) < n:
        c = sock.recv(n - len(buf))
        if not c:
            raise ConnectionError("连接被关闭")
        buf += c
    return buf


def read_packet(sock):
    """读一个 MySQL 数据包，返回 (seq, payload)。"""
    hdr = recv_exact(sock, 4)
    length = int.from_bytes(hdr[:3], "little")
    seq = hdr[3]
    return seq, recv_exact(sock, length)


def send_packet(sock, seq, payload):
    hdr = (len(payload) & 0xFFFFFF).to_bytes(3, "little") + bytes([seq & 0xFF])
    sock.sendall(hdr + payload)


# ============ 长度编码（Length-Encoded）整数/字符串 ============
def lenenc_int(n):
    if n < 251:
        return bytes([n])
    if n <= 0xFFFF:
        return bytes([0xFC]) + struct.pack("<H", n)
    if n <= 0xFFFFFF:
        return bytes([0xFD]) + struct.pack("<I", n)[:3]
    return bytes([0xFE]) + struct.pack("<Q", n)


def lenenc_bytes(b):
    return lenenc_int(len(b)) + b


def read_lenenc_int(b, pos):
    f = b[pos]
    pos += 1
    if f < 251:
        return f, pos
    if f == 0xFC:
        return int.from_bytes(b[pos:pos + 2], "little"), pos + 2
    if f == 0xFD:
        return int.from_bytes(b[pos:pos + 3], "little"), pos + 3
    return int.from_bytes(b[pos:pos + 8], "little"), pos + 8


def read_lenenc_str(b, pos):
    n, pos = read_lenenc_int(b, pos)
    s = b[pos:pos + n].decode("utf-8", "replace")
    return s, pos + n


# ============ mysql_native_password 挑战应答 ============
def native_token(scramble, pwd):
    """SHA1(SHA1(pwd)) XOR SHA1(scramble + SHA1(SHA1(pwd)))"""
    s1 = hashlib.sha1(pwd.encode("utf-8")).digest()
    s2 = hashlib.sha1(s1).digest()
    raw = hashlib.sha1(scramble + s2).digest()
    return bytes(a ^ b for a, b in zip(s1, raw))


# ============ 连接与鉴权 ============
def connect_and_auth(host, port, user, pwd):
    sock = socket.create_connection((host, port), timeout=10)
    seq, p = read_packet(sock)  # Handshake v10
    assert p[0] == 0x0A, "首包不是 MySQL 握手包（proto=%d）" % p[0]

    pos = 1
    pos = p.index(0, pos) + 1          # server version（NUL 结尾）
    pos += 4                           # connection id
    sp1 = p[pos:pos + 8]; pos += 8     # auth-plugin-data 第一部分
    pos += 1                           # filler
    pos += 2                           # capability 低 16 位
    pos += 1                           # charset
    pos += 2                           # status
    pos += 2                           # capability 高 16 位
    pos += 1                           # auth-plugin-data-len
    pos += 10                          # reserved
    sp2 = p[pos:pos + 12]; pos += 12   # auth-plugin-data 第二部分
    scramble = sp1 + sp2

    caps = (CLIENT_LONG_PASSWORD | CLIENT_PROTOCOL_41
            | CLIENT_SECURE_CONNECTION | CLIENT_PLUGIN_AUTH)
    resp = struct.pack("<I", caps)
    resp += struct.pack("<I", 0x01000000)   # max packet size = 16MB
    resp += bytes([45])                       # charset = utf8mb4
    resp += b"\x00" * 23                       # reserved
    resp += user.encode("utf-8") + b"\x00"
    resp += lenenc_bytes(native_token(scramble, pwd))
    resp += b"mysql_native_password\x00"
    send_packet(sock, 1, resp)

    seq, p = read_packet(sock)
    if p[0] == 0xFF:
        code = int.from_bytes(p[1:3], "little")
        raise ConnectionError("认证失败 [MySQL errno %d] %s" % (code, p[3:].decode("utf-8", "replace")))
    assert p[0] == 0x00, "登录未收到 OK 包（首字节 0x%02x）" % p[0]
    return sock


# ============ 执行 SQL ============
def execute(sock, sql):
    """发 COM_QUERY(0x03) 并解析返回。返回结构化结果供渲染。"""
    send_packet(sock, 0, bytes([0x03]) + sql.encode("utf-8"))
    seq, p = read_packet(sock)

    if p[0] == 0x00:  # OK 包（非结果集，如 INSERT/DDL）
        affected, _ = read_lenenc_int(p, 1)
        return {"kind": "ok", "affected": affected}
    if p[0] == 0xFF:  # ERR 包
        code = int.from_bytes(p[1:3], "little")
        msg = p[3:].decode("utf-8", "replace")
        return {"kind": "error", "code": code, "msg": msg}
    if p[0] == 0xFE and len(p) <= 9:  # 空结果集的 EOF（极少见）
        return {"kind": "ok", "affected": 0}

    # 结果集：先读列数，再读每个列的 ColumnDefinition41，再 EOF，再逐行
    ncol, pos = read_lenenc_int(p, 0)
    columns = []
    for _ in range(ncol):
        _, cp = read_packet(sock)
        cpos = 0
        for _ in range(4):           # 跳过 catalog/schema/table/org_table
            _, cpos = read_lenenc_str(cp, cpos)
        name, cpos = read_lenenc_str(cp, cpos)  # 第 5 个才是列名
        columns.append(name)
    read_packet(sock)  # EOF（列定义之后）

    rows = []
    while True:
        _, rp = read_packet(sock)
        if rp[0] == 0xFE and len(rp) <= 9:  # EOF（行结束）
            break
        rpos = 0
        row = []
        for _ in range(ncol):
            if rp[rpos] == 0xFB:       # NULL
                row.append(None)
                rpos += 1
            else:
                s, rpos = read_lenenc_str(rp, rpos)
                row.append(s)
        rows.append(row)
    return {"kind": "result", "columns": columns, "rows": rows}


def ping(sock):
    send_packet(sock, 0, bytes([0x0E]))  # COM_PING
    seq, p = read_packet(sock)
    return p[0] == 0x00


# ============ 渲染 ============
def render(res):
    if res["kind"] == "ok":
        print("OK | 影响行数=%d" % res["affected"])
    elif res["kind"] == "error":
        print("错误[errno %d]: %s" % (res["code"], res["msg"]))
    else:
        cols = res["columns"]
        rows = res["rows"]
        widths = [len(c) for c in cols]
        for r in rows:
            for i, v in enumerate(r):
                widths[i] = max(widths[i], len("" if v is None else str(v)))
        line = " | ".join(c.ljust(widths[i]) for i, c in enumerate(cols))
        print(line)
        print("-" * len(line))
        for r in rows:
            print(" | ".join(("NULL" if v is None else str(v)).ljust(widths[i])
                             for i, v in enumerate(r)))
        print("(%d 行)" % len(rows))


# ============ 入口 ============
def main():
    argv = sys.argv[1:]
    host = argv[0] if len(argv) > 0 else DEFAULT_HOST
    port = int(argv[1]) if len(argv) > 1 else DEFAULT_PORT
    user = argv[2] if len(argv) > 2 else DEFAULT_USER
    pwd = argv[3] if len(argv) > 3 else DEFAULT_PWD

    try:
        sock = connect_and_auth(host, port, user, pwd)
    except ConnectionRefusedError:
        print("[KUN-DB] 连不上 %s:%d —— 连接被拒绝。" % (host, port))
        print("         请先双击 start-kundb.bat 启动服务端，再运行本客户端。")
        return 2
    except socket.timeout:
        print("[KUN-DB] 连 %s:%d 超时。" % (host, port))
        print("         服务端可能没启动，或端口写错了。")
        return 2
    except OSError as e:
        print("[KUN-DB] 连不上 %s:%d —— %s" % (host, port, e))
        print("         请确认服务端已启动（start-kundb.bat）且端口正确。")
        return 2
    except Exception as e:  # 认证失败等
        print("[KUN-DB] 连接/认证失败：%s" % e)
        print("         默认账号是 root / 123456；如需指定：")
        print("         run-test-client.bat <host> <port> <user> <password>")
        return 2
    print("已连接到 %s:%d | 用户 %s | 协议 MySQL 线协议（握手 OK）" % (host, port, user))
    print("输入 SQL 执行（exit/quit 退出；ping 探活）：")

    try:
        while True:
            try:
                sql = input("KUN-DB> ").strip()
            except EOFError:
                break
            if not sql:
                continue
            if sql.startswith("--") or sql.startswith("#"):
                print("  -- %s" % sql.lstrip("-# "))   # 注释行：原样回显，不发给服务端
                continue
            if sql.lower() in ("exit", "quit"):
                break
            if sql.lower() == "ping":
                print("PONG" if ping(sock) else "PING 失败")
                continue
            try:
                t0 = time.time()
                res = execute(sock, sql)
                render(res)
                if res["kind"] == "result":
                    print("耗时 %.1f ms" % ((time.time() - t0) * 1000))
            except Exception as e:
                print("执行异常:", e)
    except (KeyboardInterrupt, EOFError):
        pass
    finally:
        sock.close()
        print("已断开连接。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
