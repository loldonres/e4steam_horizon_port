package com.codedisaster.steamworks;

public interface SteamLibraryLoader {
   default void setLibraryPath(String libraryPath) {
   }

   default boolean loadLibrary(String libraryName) {
      return true;
   }
}
