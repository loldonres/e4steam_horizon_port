package link.e4steam.steam;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ProcessBuilder.Redirect;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import link.e4steam.Agnos;
import link.e4steam.Config;
import link.e4steam.E4steamClient;

final class SteamOverlayRelauncher {
   private static final String MARKER_ENV = "E4STEAM_OVERLAY_RELAUNCHED";
   private static final String CAPTURED_STDIN_FILE_PROPERTY = "e4steam.capturedStdinFile";
   private static final String CAPTURED_STDIN_TRUNCATED_PROPERTY = "e4steam.capturedStdinTruncated";
   private static final long MAX_CAPTURE_BYTES = 1048576L;
   private static final int MAX_PUBLISHED_GAME_ARGUMENT_BYTES = 1048576;
   private static final int MAX_PUBLISHED_GAME_ARGUMENTS = 4096;
   private static final char PRISM_ARGUMENT_SEPARATOR = '\u001f';
   private static final String PRISM_MAIN_CLASS_PROPERTY = "org.prismlauncher.launch.mainclass";
   private static final String PRISM_GAME_ARGUMENTS_PROPERTY = "org.prismlauncher.launch.gameargs";
   private static final String MULTIMC_MAIN_CLASS_PROPERTY = "org.multimc.launch.mainclass";
   private static final String MULTIMC_GAME_ARGUMENTS_PROPERTY = "org.multimc.launch.gameargs";
   private static final String[] STDIN_LAUNCHER_WRAPPERS = new String[]{"org.prismlauncher.EntryPoint", "org.multimc.EntryPoint"};

   private SteamOverlayRelauncher() {
   }

   static void relaunchIfNeeded() {
      if (Agnos.isClient() && System.getenv("E4STEAM_OVERLAY_RELAUNCHED") == null) {
         NativePlatform platform = currentPlatform();
         if (platform != null && insertLibrariesEnvName(platform) != null) {
            if (Config.INSTANCE.overlayRelaunch.value()) {
               Optional<Path> overlay = SteamOverlayLoader.findOverlayLibrary();
               if (!overlay.isPresent()) {
                  E4steamClient.LOGGER.info("Steam Overlay renderer was not found; continuing without overlay relaunch");
               } else {
                  Optional<List<String>> originalCommand = currentJvmCommandLine(platform);
                  if (!originalCommand.isPresent()) {
                     E4steamClient.LOGGER.warn("Could not reconstruct the JVM launch command; continuing without overlay relaunch");
                  } else {
                     List<String> command = new ArrayList<>(originalCommand.get());
                     String wrapper = detectStdinLauncherWrapper(command);
                     Optional<Path> capturedStdin = capturedStdinFile();
                     if (wrapper != null && !capturedStdin.isPresent()) {
                        Optional<List<String>> direct = rewritePublishedLauncherCommand(
                           command, wrapper, publishedMainClass(wrapper), publishedGameArguments(wrapper)
                        );
                        if (!direct.isPresent()) {
                           E4steamClient.LOGGER
                              .warn(
                                 "Detected {} but it did not publish a safe direct launch command and no valid e4steam stdin-agent capture is available; continuing without overlay relaunch",
                                 wrapper
                              );
                           return;
                        }

                        command = new ArrayList<>(direct.get());
                        preservePublishedLauncherProperties(command);
                        E4steamClient.LOGGER.info("Recovered the direct Minecraft launch command from {} without a Java agent", wrapper);
                     }

                     Process child;
                     try {
                        ProcessBuilder builder = new ProcessBuilder(command);
                        Path working = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
                        if (Files.isDirectory(working)) {
                           builder.directory(working.toFile());
                        }

                        builder.redirectOutput(Redirect.INHERIT);
                        builder.redirectError(Redirect.INHERIT);
                        if (!capturedStdin.isPresent()) {
                           builder.redirectInput(Redirect.INHERIT);
                        }

                        Map<String, String> environment = builder.environment();
                        String variable = insertLibrariesEnvName(platform);
                        environment.put(variable, mergeInsertedLibrary(environment.get(variable), overlay.get().toString()));
                        configureSteamAppEnvironment(environment);
                        environment.put("E4STEAM_OVERLAY_RELAUNCHED", "1");
                        E4steamClient.LOGGER.info("Relaunching Minecraft with the Steam Overlay renderer injected");
                        child = builder.start();
                        if (capturedStdin.isPresent()) {
                           replayCapturedStdin(child, capturedStdin.get());
                        }
                     } catch (RuntimeException | IOException var12) {
                        E4steamClient.LOGGER.warn("Could not relaunch Minecraft for the Steam Overlay; continuing normally", var12);
                        return;
                     }

                     int exitCode;
                     try {
                        exitCode = child.waitFor();
                     } catch (InterruptedException var11) {
                        Thread.currentThread().interrupt();
                        child.destroy();
                        exitCode = 1;
                     }

                     System.exit(exitCode);
                  }
               }
            }
         }
      }
   }

   static String insertLibrariesEnvName(NativePlatform platform) {
      if (platform == null) {
         return null;
      } else if (platform.operatingSystem() == NativePlatform.OperatingSystem.LINUX && platform.architecture() == NativePlatform.Architecture.X86_64) {
         return "LD_PRELOAD";
      } else {
         return platform.operatingSystem() == NativePlatform.OperatingSystem.MACOS ? "DYLD_INSERT_LIBRARIES" : null;
      }
   }

   static String mergeInsertedLibrary(String existing, String overlay) {
      String checked = overlay == null ? "" : overlay.trim();
      if (checked.isEmpty()) {
         throw new IllegalArgumentException("overlay");
      } else {
         return existing != null && !existing.trim().isEmpty() ? existing + File.pathSeparator + checked : checked;
      }
   }

   static void configureSteamAppEnvironment(Map<String, String> environment) {
      if (environment == null) {
         throw new IllegalArgumentException("environment");
      } else {
         environment.put("SteamAppId", "480");
         environment.put("SteamGameId", "480");
         environment.put("SteamOverlayGameId", "480");
      }
   }

   static String detectStdinLauncherWrapper(List<String> command) {
      if (command == null) {
         return null;
      } else {
         for (String argument : command) {
            if (argument != null) {
               for (String wrapper : STDIN_LAUNCHER_WRAPPERS) {
                  if (wrapper.equals(argument)) {
                     return wrapper;
                  }
               }
            }
         }

         return null;
      }
   }

   static Optional<List<String>> rewritePublishedLauncherCommand(List<String> command, String wrapper, String mainClass, String encodedGameArguments) {
      if (command != null && wrapper != null && isSafeMainClass(mainClass) && encodedGameArguments != null && encodedGameArguments.length() <= 1048576) {
         int wrapperIndex = command.indexOf(wrapper);
         if (wrapperIndex <= 0) {
            return Optional.empty();
         } else {
            List<String> gameArguments = splitPublishedGameArguments(encodedGameArguments);
            if (gameArguments.size() > 4096) {
               return Optional.empty();
            } else {
               for (String argument : gameArguments) {
                  if (argument.indexOf(0) >= 0) {
                     return Optional.empty();
                  }
               }

               List<String> direct = new ArrayList<>(command.subList(0, wrapperIndex + 1));
               direct.set(wrapperIndex, mainClass);
               direct.addAll(gameArguments);
               return Optional.of(Collections.unmodifiableList(direct));
            }
         }
      } else {
         return Optional.empty();
      }
   }

   static List<String> splitPublishedGameArguments(String encoded) {
      if (encoded != null && !encoded.isEmpty()) {
         List<String> arguments = new ArrayList<>();
         int start = 0;

         for (int index = 0; index <= encoded.length(); index++) {
            if (index == encoded.length() || encoded.charAt(index) == 31) {
               arguments.add(encoded.substring(start, index));
               start = index + 1;
            }
         }

         return Collections.unmodifiableList(arguments);
      } else {
         return Collections.emptyList();
      }
   }

   private static boolean isSafeMainClass(String mainClass) {
      if (mainClass != null && !mainClass.isEmpty() && mainClass.length() <= 512) {
         boolean previousDot = true;

         for (int index = 0; index < mainClass.length(); index++) {
            char value = mainClass.charAt(index);
            if (value == '.') {
               if (previousDot) {
                  return false;
               }

               previousDot = true;
            } else {
               if (previousDot) {
                  if (!Character.isJavaIdentifierStart(value)) {
                     return false;
                  }
               } else if (!Character.isJavaIdentifierPart(value) && value != '$') {
                  return false;
               }

               previousDot = false;
            }
         }

         return !previousDot;
      } else {
         return false;
      }
   }

   private static String publishedMainClass(String wrapper) {
      if ("org.prismlauncher.EntryPoint".equals(wrapper)) {
         return System.getProperty("org.prismlauncher.launch.mainclass");
      } else {
         return "org.multimc.EntryPoint".equals(wrapper) ? System.getProperty("org.multimc.launch.mainclass") : null;
      }
   }

   private static String publishedGameArguments(String wrapper) {
      if ("org.prismlauncher.EntryPoint".equals(wrapper)) {
         return System.getProperty("org.prismlauncher.launch.gameargs");
      } else {
         return "org.multimc.EntryPoint".equals(wrapper) ? System.getProperty("org.multimc.launch.gameargs") : null;
      }
   }

   private static void preservePublishedLauncherProperties(List<String> command) {
      String[] properties = new String[]{
         "minecraft.launcher.brand",
         "minecraft.launcher.version",
         "org.prismlauncher.instance.name",
         "org.prismlauncher.instance.icon.id",
         "org.prismlauncher.instance.icon.path",
         "org.prismlauncher.window.title",
         "org.prismlauncher.window.dimensions",
         "multimc.instance.title",
         "multimc.instance.icon"
      };

      for (String property : properties) {
         addVmProperty(command, property, System.getProperty(property));
      }
   }

   private static void addVmProperty(List<String> command, String name, String value) {
      if (value != null && value.indexOf(0) < 0 && value.length() <= 16384) {
         String prefix = "-D" + name + "=";

         for (String argument : command) {
            if (argument != null && argument.startsWith(prefix)) {
               return;
            }
         }

         command.add(1, prefix + value);
      }
   }

   static Optional<List<String>> parseNullSeparatedCommand(byte[] raw) {
      if (raw != null && raw.length != 0) {
         List<String> result = new ArrayList<>();
         int start = 0;

         for (int index = 0; index <= raw.length; index++) {
            if (index == raw.length || raw[index] == 0) {
               if (index > start) {
                  result.add(new String(raw, start, index - start, StandardCharsets.UTF_8));
               }

               start = index + 1;
            }
         }

         return result.isEmpty() ? Optional.empty() : Optional.of(Collections.unmodifiableList(result));
      } else {
         return Optional.empty();
      }
   }

   static Optional<Path> validateCapturedStdin(Path path, boolean truncated) {
      if (path != null && !truncated) {
         try {
            Path normalized = path.toAbsolutePath().normalize();
            if (!Files.isSymbolicLink(normalized) && Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
               long size = Files.size(normalized);
               return size >= 0L && size <= 1048576L ? Optional.of(normalized) : Optional.empty();
            } else {
               return Optional.empty();
            }
         } catch (SecurityException | IOException var5) {
            return Optional.empty();
         }
      } else {
         return Optional.empty();
      }
   }

   private static NativePlatform currentPlatform() {
      try {
         return NativePlatform.normalize(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
      } catch (IOException var1) {
         return null;
      }
   }

   private static Optional<List<String>> currentJvmCommandLine(NativePlatform platform) {
      Optional<String> executable = ProcessHandle.current().info().command();
      Optional<String[]> arguments = ProcessHandle.current().info().arguments();
      if (executable.isPresent() && arguments.isPresent()) {
         List<String> command = new ArrayList<>();
         command.add(executable.get());
         Collections.addAll(command, arguments.get());
         return Optional.of(Collections.unmodifiableList(command));
      } else if (platform.operatingSystem() != NativePlatform.OperatingSystem.LINUX) {
         return Optional.empty();
      } else {
         Path cmdline = Paths.get("/proc/self/cmdline");

         try {
            return !Files.isReadable(cmdline) ? Optional.empty() : parseNullSeparatedCommand(Files.readAllBytes(cmdline));
         } catch (SecurityException | IOException var5) {
            return Optional.empty();
         }
      }
   }

   private static Optional<Path> capturedStdinFile() {
      String value = System.getProperty("e4steam.capturedStdinFile", "").trim();
      return value.isEmpty()
         ? Optional.empty()
         : validateCapturedStdin(Paths.get(value), Boolean.parseBoolean(System.getProperty("e4steam.capturedStdinTruncated", "false")));
   }

   private static void replayCapturedStdin(Process child, Path capturedFile) {
      Thread relay = new Thread(() -> {
         try (OutputStream output = child.getOutputStream()) {
            byte[] buffer = new byte[8192];

            try (InputStream captured = Files.newInputStream(capturedFile)) {
               int count;
               while ((count = captured.read(buffer)) >= 0) {
                  if (count > 0) {
                     output.write(buffer, 0, count);
                  }
               }

               output.flush();
            }

            int var13;
            while ((var13 = System.in.read(buffer)) >= 0) {
               if (var13 > 0) {
                  output.write(buffer, 0, var13);
                  output.flush();
               }
            }
         } catch (IOException var12) {
         }
      }, "e4steam-overlay-stdin-relay");
      relay.setDaemon(true);
      relay.start();
   }
}
