# Мост к NDI® SDK (libndi.so загружается динамически, см. ndi_bridge.cpp). Собирается, только если найден NDI SDK.
cmake_minimum_required(VERSION 3.22.1)
project(p2160ndi CXX)

add_library(p2160ndi SHARED ndi_bridge.cpp)
target_include_directories(p2160ndi PRIVATE ${NDI_INCLUDE_DIR})
target_link_libraries(p2160ndi log dl)
# 16 КБ страницы памяти (Android 15+): выравнивание сегментов.
target_link_options(p2160ndi PRIVATE "-Wl,-z,max-page-size=16384")
