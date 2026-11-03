package link.e4steam;

import java.io.InputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.regex.Pattern;
import link.e4steam.steam.SteamRuntime;
import link.e4steam.steam.SteamSession;

public class Doctor {
   private static final int MAX_REPORT_CHARS = 65536;
   private static final int MAX_FAILURE_DEPTH = 8;
   private static final int MAX_STACK_FRAMES = 64;
   private static final Pattern JOIN_ADDRESS = Pattern.compile(
      "(?i)(?:s-[0-9a-z]{1,13}-[0-9a-z]{1,25}|e4steam-[0-9]{1,20}-[0-9a-f]{32}|d-[0-9a-z]{1,13}-[0-9a-z]{1,13})\\.steam\\.?"
   );
   private static final Pattern STEAM_ID = Pattern.compile("(?<![0-9])7656[0-9]{13}(?![0-9])");
   private static final Pattern NAMED_SECRET = Pattern.compile("(?i)\\b(token|ticket|password|secret|cookie|authorization|gslt)(\\s*[:=]\\s*|\\s+)[^\\s,;]+");

   public static String chatSummary() {
      StringBuilder result = new StringBuilder("e4steam diagnostics\n");
      SteamRuntime runtime = SteamRuntime.get();
      Throwable runtimeFailure = null;

      try {
         result.append("Steam runtime: ").append(runtime.statusSummary()).append("\n");
         runtimeFailure = runtime.failureCause();
      } catch (Exception var6) {
         result.append("Steam runtime: unavailable\n");
         runtimeFailure = var6;
      }

      SteamSession session = E4steamClient.session;
      Throwable sessionFailure = null;
      if (session == null) {
         result.append("Steam session: none\n");
      } else {
         result.append("Steam session: ").append(session.state).append("\n");
         sessionFailure = session.failureCause;
      }

      Throwable failure = sessionFailure != null ? sessionFailure : runtimeFailure;
      if (failure == null) {
         result.append("No errors detected.");
      } else {
         result.append("Problem: ").append(shortMessage(failure)).append("\n");
         result.append("Full technical report: latest.log");
      }

      return result.toString();
   }

   public static String doctor() {
      StringBuilder result = new StringBuilder();
      result.append("mod sha512sum: ");

      try {
         MessageDigest md = MessageDigest.getInstance("SHA-512");

         try (InputStream input = Files.newInputStream(Agnos.jarPath())) {
            byte[] buffer = new byte[8192];

            int read;
            while ((read = input.read(buffer)) >= 0) {
               if (read > 0) {
                  md.update(buffer, 0, read);
               }
            }
         }

         byte[] digest = md.digest();
         result.append(HexCodec.encode(digest));
      } catch (Exception var9) {
         result.append("exception during digest:\n");
         appendThrowable(result, var9);
      }

      result.append("\n");
      SteamRuntime runtime = SteamRuntime.get();
      result.append("Steam runtime status: ");

      try {
         result.append(runtime.statusSummary()).append("\n");
      } catch (Exception var6) {
         result.append("exception while reading status:\n");
         appendThrowable(result, var6);
      }

      result.append("Steam identity: excluded from diagnostics\n");
      result.append("Steam runtime recorded exception:\n");
      Throwable runtimeFailure = null;

      try {
         runtimeFailure = runtime.failureCause();
      } catch (Exception var5) {
         runtimeFailure = var5;
      }

      if (runtimeFailure != null) {
         appendThrowable(result, runtimeFailure);
      } else {
         result.append("none recorded.\n");
      }

      result.append("Steam session:\n");
      SteamSession session = E4steamClient.session;
      if (session == null) {
         result.append("none.\n");
      } else {
         result.append("state: ").append(session.state).append("\n");
         result.append("local port: ").append(session.localPort()).append("\n");
         result.append("recorded exception:\n");
         if (session.failureCause != null) {
            appendThrowable(result, session.failureCause);
         } else {
            result.append("none recorded.\n");
         }
      }

      return bounded(result.toString());
   }

   private static void appendThrowable(StringBuilder result, Throwable throwable) {
      if (throwable != null && result.length() < 65536) {
         Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
         Throwable current = throwable;
         int depth = 0;

         int frames;
         for (frames = 0; current != null && depth++ < 8 && visited.add(current) && result.length() < 65536; current = current.getCause()) {
            if (depth > 1) {
               result.append("Caused by: ");
            }

            result.append(redactDiagnostic(current.getClass().getName()));
            String message = current.getMessage();
            if (message != null && !message.isBlank()) {
               result.append(": ").append(redactDiagnostic(message));
            }

            result.append('\n');

            for (StackTraceElement frame : current.getStackTrace()) {
               if (frames++ < 64 && result.length() < 65536) {
                  result.append("\tat ").append(redactDiagnostic(frame.toString())).append('\n');
                  continue;
               }
            }
         }

         if (current != null || frames >= 64) {
            result.append("\t... diagnostic stack truncated\n");
         }
      }
   }

   static String shortMessage(Throwable throwable) {
      Throwable current = throwable;

      String message;
      for (message = null; current != null; current = current.getCause()) {
         if (current.getMessage() != null && !current.getMessage().isBlank()) {
            message = current.getMessage();
         }
      }

      if (message == null) {
         message = throwable.getClass().getSimpleName();
      }

      message = redactDiagnostic(message).replace('\r', ' ').replace('\n', ' ').replace('\t', ' ').replaceAll("\\s+", " ").trim();
      return message.length() <= 240 ? message : message.substring(0, 237) + "...";
   }

   static String redactDiagnostic(String value) {
      if (value == null) {
         return "";
      } else {
         String redacted = JOIN_ADDRESS.matcher(value).replaceAll("<redacted-join-address>");
         redacted = STEAM_ID.matcher(redacted).replaceAll("<redacted-steam-id>");
         redacted = NAMED_SECRET.matcher(redacted).replaceAll("$1=<redacted>");
         String home = System.getProperty("user.home", "");
         if (!home.isEmpty()) {
            redacted = redacted.replace(home, "<user-home>");
         }

         return bounded(redacted);
      }
   }

   private static String bounded(String value) {
      return value.length() <= 65536 ? value : value.substring(0, 65512) + "\n... report truncated\n";
   }
}
