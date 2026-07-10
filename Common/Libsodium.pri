SODIUM_ROOT = $$(SODIUM_ROOT)

macx:isEmpty(SODIUM_ROOT) {
    SODIUM_ROOT = $$system(brew --prefix libsodium 2>/dev/null)
}

!isEmpty(SODIUM_ROOT) {
    INCLUDEPATH += $$quote($$SODIUM_ROOT/include)
    LIBS += -L$$quote($$SODIUM_ROOT/lib) -lsodium
} else:win32 {
    error("SODIUM_ROOT must point to a vcpkg libsodium installation")
} else {
    CONFIG += link_pkgconfig
    packagesExist(libsodium) {
        PKGCONFIG += libsodium
    } else {
        error("libsodium development files are required (pkg-config: libsodium)")
    }
}
