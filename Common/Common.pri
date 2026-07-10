INCLUDEPATH += $$PWD
DEPENDPATH  += $$PWD

# Homebrew Qt 6.9 still inherits the retired AGL framework from mac.conf.
# Modern macOS SDKs retain OpenGL compatibility but no longer ship AGL.
macx {
    CONFIG -= link_prl
    QMAKE_LIBS_OPENGL = -framework OpenGL
    QMAKE_INCDIR_OPENGL = /System/Library/Frameworks/OpenGL.framework/Headers
}

HEADERS += \
    $$PWD/Protocol.h \
    $$PWD/Message.h

SOURCES += \
    $$PWD/Message.cpp
