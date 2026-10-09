# Keep service entry points referenced from the manifest (R8 keeps these by default; listed for clarity).
-keep class com.carmirror.app.InputInjectorService { *; }
-keep class com.carmirror.app.CarMirrorAppService { *; }
