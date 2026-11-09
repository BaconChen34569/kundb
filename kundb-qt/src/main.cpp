#include <QApplication>
#include <QPainter>
#include <QPixmap>
#include <QIcon>
#include "mainwindow.h"
#include "sqlresult.h"

// 程序图标：蓝色圆角底 + 白色数据库圆柱（与 kundb.ico 同一设计，运行时绘制）
static QIcon makeAppIcon() {
    QIcon icon;
    for (int s : {16, 24, 32, 48, 64, 128, 256}) {
        QPixmap pm(s, s);
        pm.fill(Qt::transparent);
        QPainter p(&pm);
        p.setRenderHint(QPainter::Antialiasing);
        // 蓝色圆角方块
        QLinearGradient grad(0, 0, 0, s);
        grad.setColorAt(0, QColor(36, 130, 226));
        grad.setColorAt(1, QColor(16, 74, 150));
        p.setPen(Qt::NoPen);
        p.setBrush(grad);
        qreal r = s * 0.22;
        p.drawRoundedRect(QRectF(r * 0.2, r * 0.2, s - r * 0.4, s - r * 0.4), r, r);
        // 白色圆柱
        p.setBrush(Qt::white);
        qreal cx = s * 0.5, rx = s * 0.30, ry = s * 0.105;
        qreal topY = s * 0.34, botY = s * 0.66;
        p.drawEllipse(QPointF(cx, botY), rx, ry);
        p.drawRect(QRectF(cx - rx, topY, rx * 2, botY - topY));
        p.drawEllipse(QPointF(cx, topY), rx, ry);
        // 顶面用背景蓝描一圈，区分顶面
        p.setBrush(Qt::NoPen);
        p.setPen(QPen(QColor(24, 96, 180), qMax(1.0, s * 0.03)));
        p.drawEllipse(QPointF(cx, topY), rx, ry);
        icon.addPixmap(pm);
    }
    return icon;
}

int main(int argc, char* argv[]) {
    QApplication app(argc, argv);
    QApplication::setApplicationName("KUN-DB 客户端");
    app.setWindowIcon(makeAppIcon());

    // 跨线程信号需注册自定义类型
    qRegisterMetaType<SqlResult>("SqlResult");
    qRegisterMetaType<ColumnInfo>("ColumnInfo");
    qRegisterMetaType<QList<ColumnInfo>>("QList<ColumnInfo>");

    MainWindow w;
    w.show();
    return app.exec();
}
