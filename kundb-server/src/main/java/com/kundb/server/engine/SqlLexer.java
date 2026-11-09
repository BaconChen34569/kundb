package com.kundb.server.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * 最小 SQL 词法分析器（第二步专用，仅覆盖内存引擎支持的子集）。
 * <p>正式 SQL 引擎将在 W3 用设计文档 §6.2 的"手写 Lexer + 递归下降 Parser"替换本类。
 * 这里先把 CREATE/DROP/USE/INSERT/SELECT/SHOW/DESC 用到的 token 切出来，足以让服务端被客户端连上跑通协议。</p>
 */
public final class SqlLexer {

    public enum TokenType {
        IDENT, NUMBER, STRING, LPAREN, RPAREN, COMMA, DOT, STAR, SEMI, OP, EOF
    }

    public static final class Token {
        public final TokenType type;
        public final String text;

        public Token(TokenType type, String text) {
            this.type = type;
            this.text = text;
        }

        @Override
        public String toString() {
            return type + "(" + text + ")";
        }
    }

    private SqlLexer() {
    }

    public static List<Token> tokenize(String sql) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            // 行注释 --
            if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                i += 2;
                while (i < n && sql.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            // 块注释 /* */
            if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(sql.charAt(i) == '*' && sql.charAt(i + 1) == '/')) {
                    i++;
                }
                i += 2;
                continue;
            }
            // 字符串字面量：单引号 'abc' 或双引号 "abc"。
            // MySQL 默认模式下双引号也是字符串字面量（除非开启 ANSI_QUOTES），故一并支持；
            // '' 或 "" 在各自引号内转义为单个引号，\ 仍为转义符。
            if (c == '\'' || c == '"') {
                char q = c;
                i++;
                StringBuilder sb = new StringBuilder();
                while (i < n) {
                    char ch = sql.charAt(i);
                    if (ch == '\\') {
                        i++;
                        if (i < n) {
                            sb.append(sql.charAt(i));
                        }
                        i++;
                        continue;
                    }
                    if (ch == q) {
                        if (i + 1 < n && sql.charAt(i + 1) == q) {
                            sb.append(q);
                            i += 2;
                            continue;
                        }
                        break;
                    }
                    sb.append(ch);
                    i++;
                }
                i++; // 跳过结尾引号
                tokens.add(new Token(TokenType.STRING, sb.toString()));
                continue;
            }
            // 数字（含小数点）
            if (Character.isDigit(c) || (c == '.' && i + 1 < n && Character.isDigit(sql.charAt(i + 1)))) {
                int start = i;
                while (i < n && (Character.isDigit(sql.charAt(i)) || sql.charAt(i) == '.')) {
                    i++;
                }
                tokens.add(new Token(TokenType.NUMBER, sql.substring(start, i)));
                continue;
            }
            // 标识符 / 关键字（NULL/TRUE/FALSE 也归为 IDENT，由解析层判别）
            if (Character.isLetter(c) || c == '_') {
                int start = i;
                while (i < n && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '_')) {
                    i++;
                }
                tokens.add(new Token(TokenType.IDENT, sql.substring(start, i)));
                continue;
            }
            // 符号与运算符
            switch (c) {
                case '(' -> {
                    tokens.add(new Token(TokenType.LPAREN, "("));
                    i++;
                }
                case ')' -> {
                    tokens.add(new Token(TokenType.RPAREN, ")"));
                    i++;
                }
                case ',' -> {
                    tokens.add(new Token(TokenType.COMMA, ","));
                    i++;
                }
                case '.' -> {
                    tokens.add(new Token(TokenType.DOT, "."));
                    i++;
                }
                case '*' -> {
                    tokens.add(new Token(TokenType.STAR, "*"));
                    i++;
                }
                case '+' -> {
                    tokens.add(new Token(TokenType.OP, "+"));
                    i++;
                }
                case '-' -> {
                    tokens.add(new Token(TokenType.OP, "-"));
                    i++;
                }
                case '@' -> {
                    tokens.add(new Token(TokenType.IDENT, "@"));
                    i++;
                }
                case ';' -> {
                    tokens.add(new Token(TokenType.SEMI, ";"));
                    i++;
                }
                case '=' -> {
                    tokens.add(new Token(TokenType.OP, "="));
                    i++;
                }
                case '<' -> {
                    if (i + 1 < n && sql.charAt(i + 1) == '>') {
                        tokens.add(new Token(TokenType.OP, "<>"));
                        i += 2;
                    } else if (i + 1 < n && sql.charAt(i + 1) == '=') {
                        tokens.add(new Token(TokenType.OP, "<="));
                        i += 2;
                    } else {
                        tokens.add(new Token(TokenType.OP, "<"));
                        i++;
                    }
                }
                case '>' -> {
                    if (i + 1 < n && sql.charAt(i + 1) == '=') {
                        tokens.add(new Token(TokenType.OP, ">="));
                        i += 2;
                    } else {
                        tokens.add(new Token(TokenType.OP, ">"));
                        i++;
                    }
                }
                default -> throw new RuntimeException("无法识别的字符: '" + c + "'");
            }
        }
        tokens.add(new Token(TokenType.EOF, ""));
        return tokens;
    }
}
