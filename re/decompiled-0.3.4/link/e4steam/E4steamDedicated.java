package link.e4steam;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.SocketAddress;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import link.e4steam.internal.addon.AddonCandidate;
import link.e4steam.internal.api.CoreApiPlatform;
import link.e4steam.internal.api.RuntimeEnvironment;
import link.e4steam.internal.dedicated.DedicatedRuntimeConfig;
import link.e4steam.internal.dedicated.DedicatedServerController;
import link.e4steam.internal.dedicated.DedicatedServerPropertiesValidator;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class E4steamDedicated {
   private static final Logger LOGGER = LogManager.getLogger("e4steam");
   private static final AtomicBoolean INITIALIZED = new AtomicBoolean();
   private static volatile DedicatedServerController controller;

   private E4steamDedicated() {
   }

   public static void init(RuntimeEnvironment environment, List<AddonCandidate> candidates) {
      if (INITIALIZED.compareAndSet(false, true)) {
         DedicatedRuntimeConfig config;
         try {
            config = DedicatedRuntimeConfig.load(
               Paths.get(System.getProperty("user.dir", "."), "config", "e4steam-dedicated.toml"), System.getenv(), System.getProperties()
            );
         } catch (RuntimeException | IOException var4) {
            throw new IllegalStateException("Invalid e4steam dedicated configuration", var4);
         }

         if (config.enabled()) {
            validateServerProperties();
         }

         controller = DedicatedServerController.install(config);
         CoreApiPlatform.start(environment, candidates);
         if (config.enabled()) {
            LOGGER.info("e4steam dedicated mode enabled: {}", config);
         } else {
            LOGGER.info("e4steam dedicated backend is installed but disabled");
         }
      }
   }

   public static void minecraftListening(InetAddress bindAddress, int port) {
      DedicatedServerController active = controller;
      if (active != null) {
         active.minecraftListening(bindAddress, port);
      }
   }

   public static void minecraftReady() {
      DedicatedServerController active = controller;
      if (active != null) {
         active.minecraftReady();
      }
   }

   public static void validateMinecraftBind(InetAddress bindAddress) {
      DedicatedServerController active = controller;
      if (active != null) {
         active.validateMinecraftBind(bindAddress);
      }
   }

   public static void minecraftStopped() {
      DedicatedServerController active = controller;
      if (active != null) {
         active.minecraftStopped();
      }
   }

   public static long authenticatedMinecraftPeer(SocketAddress remoteAddress) {
      DedicatedServerController active = controller;
      return active == null ? 0L : active.authenticatedMinecraftPeer(remoteAddress);
   }

   public static boolean requiresAuthenticatedIngress() {
      DedicatedServerController active = controller;
      return active != null && active.requiresAuthenticatedIngress();
   }

   public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
      dispatcher.register(
         (LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)Commands.literal(
                              "e4steam-dedicated"
                           )
                           .requires(source -> CommandPermissionCompat.hasPermission(source, 4)))
                        .then(Commands.literal("status").executes(context -> {
                           DedicatedServerController active = controller;
                           String status = active == null ? "unavailable" : active.safeStatus();
                           DedicatedCommandCompat.success((CommandSourceStack)context.getSource(), status);
                           return 1;
                        })))
                     .then(Commands.literal("descriptor").executes(context -> {
                        DedicatedServerController active = controller;
                        String descriptor = active == null ? "" : active.descriptor();
                        if (descriptor.isEmpty()) {
                           DedicatedCommandCompat.failure((CommandSourceStack)context.getSource(), "e4steam dedicated is not accepting connections");
                           return 0;
                        } else {
                           DedicatedCommandCompat.success((CommandSourceStack)context.getSource(), "e4steam descriptor: " + descriptor);
                           return 1;
                        }
                     })))
                  .then(Commands.literal("stop").executes(context -> {
                     DedicatedServerController active = controller;
                     if (active != null) {
                        active.minecraftStopped();
                     }

                     DedicatedCommandCompat.success((CommandSourceStack)context.getSource(), "e4steam dedicated is draining");
                     return 1;
                  })))
               .then(identityCommand("ban", true, true)))
            .then(identityCommand("unban", false, true))
      );
   }

   private static LiteralArgumentBuilder<CommandSourceStack> identityCommand(String literal, boolean present, boolean ban) {
      return (LiteralArgumentBuilder<CommandSourceStack>)Commands.literal(literal)
         .then(
            Commands.argument("identity", StringArgumentType.word())
               .executes(
                  context -> {
                     DedicatedServerController active = controller;
                     if (active == null) {
                        DedicatedCommandCompat.failure((CommandSourceStack)context.getSource(), "e4steam dedicated is unavailable");
                        return 0;
                     } else {
                        String identity = StringArgumentType.getString(context, "identity");

                        try {
                           boolean changed = ban ? active.updateBan(identity, present) : active.updateWhitelist(identity, present);
                           String normalized = active.normalizedIdentity(identity);
                           DedicatedCommandCompat.success(
                              (CommandSourceStack)context.getSource(), "e4steam access " + (changed ? "updated: " : "unchanged: ") + normalized
                           );
                           return changed ? 1 : 0;
                        } catch (RuntimeException var7) {
                           DedicatedCommandCompat.failure((CommandSourceStack)context.getSource(), "Expected a SteamID64 or e4steam-derived UUID");
                           return 0;
                        }
                     }
                  }
               )
         );
   }

   private static void validateServerProperties() {
      Path path = Paths.get(System.getProperty("user.dir", "."), "server.properties").toAbsolutePath().normalize();
      Properties properties = new Properties();

      try {
         if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("e4steam dedicated requires server.properties with server-ip=127.0.0.1");
         }

         try (InputStream input = Files.newInputStream(path)) {
            properties.load(input);
         }
      } catch (IOException var7) {
         throw new IllegalStateException("Could not validate dedicated server.properties", var7);
      }

      DedicatedServerPropertiesValidator.Validation validation = DedicatedServerPropertiesValidator.validate(properties);
      if (!validation.allowed()) {
         throw new IllegalStateException("Unsafe e4steam dedicated server.properties: " + validation.category());
      }
   }
}
