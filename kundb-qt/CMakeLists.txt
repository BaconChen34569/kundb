cmake_minimum_required(VERSION 3.16)
project(kundb-qt LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_AUTOMOC ON)
set(CMAKE_AUTORCC ON)
set(CMAKE_AUTOUIC ON)

find_package(Qt6 REQUIRED COMPONENTS Widgets Network)

file(GLOB SRC CONFIGURE_DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/src/*.cpp")
# WIN32：GUI 子系统，双击启动不再弹黑色控制台窗口
add_executable(kundb-qt WIN32 ${SRC})
# exe 文件图标（资源管理器 / 任务栏 / 标题栏）
target_sources(kundb-qt PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/kundb-qt.rc")
target_link_libraries(kundb-qt PRIVATE Qt6::Widgets Qt6::Network)
target_include_directories(kundb-qt PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/src")

# 无界面的协议自测：直接用 SqlConnection 连真实 KUN-DB 服务端，验证线协议实现
add_executable(kundb-qt-test "${CMAKE_CURRENT_SOURCE_DIR}/test_protocol.cpp"
                           "${CMAKE_CURRENT_SOURCE_DIR}/src/sqlconnection.cpp")
target_link_libraries(kundb-qt-test PRIVATE Qt6::Core Qt6::Network)
target_include_directories(kundb-qt-test PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/src")
