# -*- mode: python ; coding: utf-8 -*-


a = Analysis(
    ['desktop/app.py'],
    pathex=[],
    binaries=[],
    # 'desktop' ships as data because desktop/ is also where the app looks for
    # its assets at runtime. auth_token and safe_filename are imported by *name*
    # from modules that live in it, which PyInstaller's static analysis does not
    # follow across the sys.path manipulation those modules do -- so without them
    # named here the frozen build fails at startup with ModuleNotFoundError.
    datas=[('desktop', 'desktop'), ('auth_token.py', '.'), ('safe_filename.py', '.')],
    hiddenimports=['qrcode', 'PIL', 'PyQt6', 'auth_token', 'safe_filename'],
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=[],
    noarchive=False,
    optimize=0,
)
pyz = PYZ(a.pure)

exe = EXE(
    pyz,
    a.scripts,
    [],
    exclude_binaries=True,
    name='NexusFlow',
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=True,
    console=True,
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
)
coll = COLLECT(
    exe,
    a.binaries,
    a.datas,
    strip=False,
    upx=True,
    upx_exclude=[],
    name='NexusFlow',
)
