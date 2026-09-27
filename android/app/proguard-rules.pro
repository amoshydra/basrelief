# minifyEnabled is false, so these rules are not applied to the current
# release build. They exist so that turning minification on stays safe.

# Keep the renderer/service entry points referenced from the manifest.
-keep class com.amoshydra.basrelief.DepthWallpaperService { *; }
-keep class com.amoshydra.basrelief.DepthWallpaperService$* { *; }
