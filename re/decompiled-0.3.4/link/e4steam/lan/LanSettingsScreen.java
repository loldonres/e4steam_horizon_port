package link.e4steam.lan;

import java.util.Arrays;
import link.e4steam.Config;
import link.e4steam.E4steamClient;
import link.e4steam.MinecraftUiCompat;
import link.e4steam.Mirror;
import link.e4steam.internal.api.CoreAccessModeCatalog;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;

public final class LanSettingsScreen extends Screen {
   private final Screen parent;
   private final Screen worldOptions;
   private LanSettings.Snapshot draft;
   private CoreAccessModeCatalog.Selection access;
   private EditBox players;
   private EditBox port;
   private EditBox publicName;
   private Button onlineButton;
   private Button done;
   private boolean saving;
   private boolean published;
   private boolean playersValid = true;
   private boolean portValid = true;
   private int panelLeft;
   private int panelTop;
   private int panelWidth;
   private int panelHeight;
   private int gameTop;
   private int networkTop;
   private int sectionHeight;
   private int columnWidth;
   private int doneX;
   private int doneY;
   private int doneWidth;
   private int tabsLeft;
   private int tabWidth;

   public LanSettingsScreen(Screen parent) {
      this(parent, null);
   }

   public LanSettingsScreen(Screen parent, Screen worldOptions) {
      super(Mirror.translatable("text.e4steam_minecraft.lan.settings"));
      this.parent = parent;
      this.worldOptions = worldOptions;
      this.draft = LanSettings.current();
      this.access = CoreAccessModeCatalog.normalize(E4steamClient.selectedAccessMode, E4steamClient.selectedCustomAccessMode);
   }

   protected void init() {
      IntegratedServer server = this.minecraft.getSingleplayerServer();
      if (server == null) {
         this.onClose();
      } else {
         if (this.worldOptions != null) {
            this.addWorldSettingsTabs();
         }

         this.published = LanServerSettingsApplier.published(server);
         LanSettings.Snapshot active = LanSettings.active(server);
         if (active != null && this.players == null) {
            this.draft = active;
         }

         int initialPort = this.published ? LanServerSettingsApplier.port(server) : this.draft.port();
         if (initialPort < 1 || initialPort > 65535) {
            initialPort = 25565;
         }

         String playersText = this.numericValue(
            this.players == null ? Integer.toString(this.draft.maxPlayers()) : this.players.getValue(), this.draft.maxPlayers()
         );
         String portText = this.numericValue(this.port == null ? Integer.toString(initialPort) : this.port.getValue(), initialPort);
         this.panelWidth = Math.min(420, this.width - 24);
         this.panelHeight = Math.min(288, this.height - 12);
         this.panelLeft = (this.width - this.panelWidth) / 2;
         this.panelTop = (this.height - this.panelHeight) / 2;
         boolean compact = this.panelHeight < 252;
         this.sectionHeight = compact ? 68 : 80;
         this.gameTop = this.panelTop + (compact ? 28 : 40);
         this.networkTop = this.gameTop + this.sectionHeight + 4;
         int left = this.panelLeft + 22;
         int contentWidth = this.panelWidth - 44;
         this.columnWidth = (contentWidth - 8) / 2;
         int right = left + this.columnWidth + 8;
         int gameRow = this.gameTop + 22;
         Button mode = (Button)this.addRenderableWidget(MinecraftUiCompat.button(this.gameModeLabel(), button -> {
            String[] modes = new String[]{"survival", "creative", "adventure", "spectator"};
            int index = Arrays.asList(modes).indexOf(this.draft.gameMode());
            this.draft = this.draft.withGameMode(modes[(index + 1) % modes.length]);
            button.setMessage(this.gameModeLabel());
            MinecraftUiCompat.tooltip(button, Mirror.translatable("text.e4steam_minecraft.lan.gameMode", this.gameModeLabel()));
         }, left, gameRow, this.columnWidth, 20));
         MinecraftUiCompat.tooltip(mode, Mirror.translatable("text.e4steam_minecraft.lan.gameMode", this.gameModeLabel()));
         Button cheats = (Button)this.addRenderableWidget(MinecraftUiCompat.button(this.state("cheats", this.draft.allowCheats()), button -> {
            this.draft = this.draft.withAllowCheats(!this.draft.allowCheats());
            button.setMessage(this.state("cheats", this.draft.allowCheats()));
         }, right, gameRow, this.columnWidth, 20));
         MinecraftUiCompat.tooltip(cheats, Mirror.translatable("text.e4steam_minecraft.lan.cheatsHelp"));
         this.addRenderableWidget(MinecraftUiCompat.button(this.state("pvp", this.draft.pvp()), button -> {
            this.draft = this.draft.withPvp(!this.draft.pvp());
            button.setMessage(this.state("pvp", this.draft.pvp()));
         }, left, gameRow + 24, this.columnWidth, 20));
         this.players = this.numberBox(right + this.columnWidth - 44, gameRow + 24, 44, this.draft.maxPlayers());
         this.players.setMaxLength(2);
         this.players.setValue(playersText);
         MinecraftUiCompat.tooltip(this.players, Mirror.translatable("text.e4steam_minecraft.lan.playersHelp"));
         int networkRow = this.networkTop + 22;
         Button accessButton = (Button)this.addRenderableWidget(MinecraftUiCompat.button(this.accessLabel(), button -> {
            this.access = CoreAccessModeCatalog.next(this.access.mode(), this.access.customModeId());
            button.setMessage(this.accessLabel());
            MinecraftUiCompat.tooltip(button, this.accessLabel());
            this.updatePublicNameVisibility();
         }, left, networkRow, contentWidth, 20));
         accessButton.active = Config.INSTANCE.hostEnabled.value();
         MinecraftUiCompat.tooltip(accessButton, this.accessLabel());
         Button online = (Button)this.addRenderableWidget(MinecraftUiCompat.button(this.state("onlineMode", this.draft.onlineMode()), button -> {
            this.draft = this.draft.withOnlineMode(!this.draft.onlineMode());
            button.setMessage(this.state("onlineMode", this.draft.onlineMode()));
         }, left, networkRow + 24, this.columnWidth, 20));
         this.onlineButton = online;
         MinecraftUiCompat.tooltip(online, Mirror.translatable("text.e4steam_minecraft.lan.onlineHelp"));
         this.port = this.numberBox(right + this.columnWidth - 64, networkRow + 24, 64, initialPort);
         this.port.setEditable(!this.published);
         this.port.setValue(portText);
         MinecraftUiCompat.tooltip(
            this.port, Mirror.translatable(this.published ? "text.e4steam_minecraft.lan.portLocked" : "text.e4steam_minecraft.lan.portHelp")
         );
         String nameText = this.publicName == null ? Config.INSTANCE.publicServerName.value() : this.publicName.getValue();
         if (nameText.isBlank()) {
            nameText = this.minecraft.getUser().getName() + " server";
         }

         this.publicName = (EditBox)this.addRenderableWidget(
            new EditBox(this.font, left + 88, networkRow + 48, contentWidth - 88, 20, Mirror.literal("Имя сервера"))
         );
         this.publicName.setMaxLength(48);
         this.publicName.setValue(nameText);
         this.updatePublicNameVisibility();
         int footer = this.networkTop + this.sectionHeight + 24 + (compact ? 8 : 12);
         int footerWidth = (this.panelWidth - 32) / 2;
         this.addRenderableWidget(
            MinecraftUiCompat.button(Mirror.translatable("gui.cancel"), button -> this.onClose(), this.panelLeft + 12, footer, footerWidth, 20)
         );
         this.doneX = this.panelLeft + this.panelWidth - 12 - footerWidth;
         this.doneY = footer;
         this.doneWidth = footerWidth;
         this.done = (Button)this.addRenderableWidget(
            MinecraftUiCompat.button(
               Mirror.translatable(this.published ? "text.e4steam_minecraft.lan.apply" : "lanServer.start"),
               button -> this.save(),
               this.panelLeft + this.panelWidth - 12 - footerWidth,
               footer,
               footerWidth,
               20
            )
         );
         MinecraftUiCompat.numericResponder(this.players, value -> this.validate());
         MinecraftUiCompat.numericResponder(this.port, value -> this.validate());
         this.validate();
      }
   }

   private void addWorldSettingsTabs() {
      int availableWidth = Math.max(1, this.width - 24);
      this.tabWidth = Math.min(168, Math.max(70, (availableWidth - 8) / 2));
      this.tabsLeft = (this.width - this.tabWidth * 2 - 8) / 2;
      Button lanTab = (Button)this.addRenderableWidget(
         MinecraftUiCompat.button(Mirror.translatable("text.e4steam_minecraft.lan.tabLan"), button -> {}, this.tabsLeft, 6, this.tabWidth, 20)
      );
      lanTab.active = false;
      this.addRenderableWidget(
         MinecraftUiCompat.button(
            Mirror.translatable("text.e4steam_minecraft.lan.tabWorld"),
            button -> WorldOptionsScreenSupport.openNativeWorldTab(this.minecraft, this.worldOptions, this.parent),
            this.tabsLeft + this.tabWidth + 8,
            6,
            this.tabWidth,
            20
         )
      );
   }

   public void renderLanPanel(Object context) {
      LanScreenGraphics graphics = new LanScreenGraphics(context);
      graphics.fill(0, 0, this.width, this.height, -1207300071);
      graphics.fill(this.panelLeft + 3, this.panelTop + 4, this.panelLeft + this.panelWidth + 3, this.panelTop + this.panelHeight + 4, 1342177280);
      graphics.fill(this.panelLeft, this.panelTop, this.panelLeft + this.panelWidth, this.panelTop + this.panelHeight, -233430996);
      graphics.fill(this.panelLeft, this.panelTop, this.panelLeft + 1, this.panelTop + this.panelHeight, -13415842);
      graphics.fill(this.panelLeft + this.panelWidth - 1, this.panelTop, this.panelLeft + this.panelWidth, this.panelTop + this.panelHeight, -13415842);
      graphics.fill(this.panelLeft, this.panelTop + this.panelHeight - 1, this.panelLeft + this.panelWidth, this.panelTop + this.panelHeight, -13415842);
      graphics.fill(this.panelLeft, this.panelTop, this.panelLeft + this.panelWidth, this.panelTop + 2, -11219012);
      this.section(graphics, this.gameTop, "rules");
      this.section(graphics, this.networkTop, "connection");
   }

   private void renderLanLabels(LanScreenGraphics graphics) {
      graphics.drawCenteredString(this.font, this.title, this.width / 2, this.panelTop + 12, 15857403);
      if (this.panelHeight >= 252) {
         graphics.drawCenteredString(
            this.font,
            Mirror.translatable(this.published ? "text.e4steam_minecraft.lan.live" : "text.e4steam_minecraft.lan.ready"),
            this.width / 2,
            this.panelTop + 27,
            this.published ? 8249799 : 10005181
         );
      }

      this.sectionLabel(graphics, this.gameTop, "rules");
      this.sectionLabel(graphics, this.networkTop, "connection");
      int right = this.panelLeft + 22 + this.columnWidth + 8;
      graphics.drawString(
         this.font, Mirror.translatable("text.e4steam_minecraft.lan.players"), right, this.gameTop + 52, this.playersValid ? 12043989 : 16748174, false
      );
      if (this.isPublicServer()) {
         graphics.drawString(this.font, Mirror.literal("Имя сервера"), this.panelLeft + 22, this.networkTop + 76, 12043989, false);
      }

      graphics.drawString(
         this.font,
         Mirror.translatable("text.e4steam_minecraft.lan.port"),
         right,
         this.networkTop + 52,
         !this.portValid ? 16748174 : (this.published ? 7570583 : 12043989),
         false
      );
      if (!this.playersValid) {
         this.outline(graphics, this.players, 44);
      }

      if (!this.portValid) {
         this.outline(graphics, this.port, 64);
      }
   }

   public void renderLanIndicators(Object context) {
      LanScreenGraphics graphics = new LanScreenGraphics(context);
      this.renderLanLabels(graphics);
      if (this.worldOptions != null) {
         graphics.fill(this.tabsLeft, 27, this.tabsLeft + this.tabWidth, 29, -11219012);
      }

      int right = this.panelLeft + 22 + this.columnWidth + 8;
      this.indicator(graphics, right + 3, this.gameTop + 25, this.draft.allowCheats());
      this.indicator(graphics, this.panelLeft + 25, this.gameTop + 49, this.draft.pvp());
      this.indicator(graphics, this.panelLeft + 25, this.networkTop + 49, this.draft.onlineMode());
      graphics.fill(this.doneX, this.doneY + 19, this.doneX + this.doneWidth, this.doneY + 20, this.done.active ? -11219012 : -12365730);
   }

   private void section(LanScreenGraphics graphics, int top, String key) {
      int left = this.panelLeft + 12;
      graphics.fill(
         left, top, this.panelLeft + this.panelWidth - 12, top + (key.equals("connection") ? this.sectionHeight + 24 : this.sectionHeight), -14668485
      );
      graphics.fill(left, top, this.panelLeft + this.panelWidth - 12, top + 1, -13943475);
      graphics.fill(
         left,
         top + (key.equals("connection") ? this.sectionHeight + 24 : this.sectionHeight) - 1,
         this.panelLeft + this.panelWidth - 12,
         top + (key.equals("connection") ? this.sectionHeight + 24 : this.sectionHeight),
         -13943475
      );
      graphics.fill(left, top, left + 2, top + (key.equals("connection") ? this.sectionHeight + 24 : this.sectionHeight), -13415842);
   }

   private void sectionLabel(LanScreenGraphics graphics, int top, String key) {
      int left = this.panelLeft + 12;
      graphics.drawString(this.font, Mirror.translatable("text.e4steam_minecraft.lan." + key), left + 10, top + 7, 9624014, false);
   }

   private void indicator(LanScreenGraphics graphics, int x, int y, boolean enabled) {
      graphics.fill(x, y, x + 2, y + 14, enabled ? -10037841 : -9075562);
   }

   private void outline(LanScreenGraphics graphics, EditBox input, int size) {
      int x = this.panelLeft + 22 + this.columnWidth + 8 + this.columnWidth - size;
      int y = (input == this.players ? this.gameTop : this.networkTop) + 46;
      graphics.fill(x - 2, y - 2, x + size + 2, y - 1, -29042);
      graphics.fill(x - 2, y + 21, x + size + 2, y + 22, -29042);
   }

   private EditBox numberBox(int x, int y, int size, int value) {
      EditBox input = new EditBox(this.font, x, y, size, 20, Mirror.literal(""));
      input.setMaxLength(5);
      input.setValue(Integer.toString(value));
      return (EditBox)this.addRenderableWidget(input);
   }

   private String numericValue(String value, int fallback) {
      String digits = value == null ? "" : value.replaceAll("[^0-9]", "");
      return digits.isEmpty() ? Integer.toString(fallback) : digits;
   }

   private void validate() {
      if (this.done != null && this.players != null && this.port != null) {
         String playerText = this.normalizeDigits(this.players.getValue());
         if (!playerText.equals(this.players.getValue())) {
            if (playerText.isEmpty() && !this.players.getValue().isEmpty()) {
               playerText = "8";
            }

            this.players.setValue(playerText);
         }

         Integer count = this.number(playerText, 2, 64);
         Integer selectedPort;
         if (this.published) {
            selectedPort = this.draft.port();
         } else {
            String rawPort = this.port.getValue();
            String portText = this.normalizeDigits(rawPort);
            if (!portText.equals(rawPort)) {
               if (portText.isEmpty() && !rawPort.isEmpty()) {
                  portText = Integer.toString(25565);
               }

               this.port.setValue(portText);
            }

            selectedPort = portText.trim().isEmpty() ? 25565 : this.number(portText, 1, 65535);
         }

         this.playersValid = count != null;
         this.portValid = selectedPort != null;
         if (this.playersValid && this.portValid) {
            this.draft = this.draft.withMaxPlayers(count).withPort(selectedPort);
         }

         this.done.active = this.playersValid && this.portValid && !this.saving;
      }
   }

   private String normalizeDigits(String value) {
      return value == null ? "" : value.replaceAll("[^0-9]", "");
   }

   private Integer number(String text, int min, int max) {
      try {
         int value = Integer.parseInt(text);
         return value >= min && value <= max ? value : null;
      } catch (NumberFormatException var5) {
         return null;
      }
   }

   private boolean isPublicServer() {
      return this.access.customModeId().equals("k2studio:public") || this.access.translationKey().equals("e4steam_public_servers.access.public");
   }

   private void updatePublicNameVisibility() {
      if (this.publicName != null) {
         this.publicName.visible = this.isPublicServer();
         this.onlineButton.visible = true;
         this.port.visible = true;
      }
   }

   private void applyPublicName() {
      if (this.isPublicServer()) {
         String name = this.publicName.getValue().trim();
         if (name.isBlank()) {
            name = this.minecraft.getUser().getName() + " server";
         }

         try {
            Class<?> runtime = Class.forName("dev.k2studio.e4steam.publicservers.common.PublicServersRuntime");
            runtime.getMethod("setIntegratedServerName", String.class).invoke(null, name);
            Config.INSTANCE.publicServerName.setValue(name, true);
         } catch (ReflectiveOperationException var3) {
            throw new IllegalStateException("Public Servers name bridge is unavailable", var3);
         }
      }
   }

   private void save() {
      this.validate();
      if (this.done.active) {
         if (this.port.getValue().trim().isEmpty()) {
            this.port.setValue(Integer.toString(this.draft.port()));
         }

         if (this.access.requiresConfirmation() && !E4steamClient.customAccessConfirmed(this.access.customModeId())) {
            MinecraftUiCompat.setScreen(this.minecraft, new ConfirmScreen(confirmed -> {
               if (confirmed) {
                  E4steamClient.confirmCustomAccess(this.access.customModeId());
               }

               MinecraftUiCompat.setScreen(this.minecraft, this);
               if (confirmed) {
                  this.save();
               }
            }, Mirror.translatable(this.access.confirmationTitleKey()), Mirror.translatable(this.access.confirmationMessageKey())));
         } else {
            IntegratedServer server = this.minecraft.getSingleplayerServer();
            if (server == null) {
               this.onClose();
            } else {
               this.saving = true;
               this.done.active = false;
               LanSettings.Snapshot selected = this.draft;

               try {
                  this.applyPublicName();
               } catch (RuntimeException var4) {
                  this.saving = false;
                  E4steamClient.LOGGER.warn("Cannot set public server name", var4);
                  this.done.setMessage(Mirror.translatable("text.e4steam_minecraft.lan.failed"));
                  this.validate();
                  return;
               }

               E4steamClient.selectedAccessMode = this.access.mode();
               E4steamClient.selectedCustomAccessMode = this.access.customModeId();
               server.execute(() -> {
                  try {
                     if (this.minecraft.getSingleplayerServer() != server) {
                        throw new IllegalStateException("The integrated world changed");
                     }

                     LanServerSettingsApplier.apply(server, selected);
                     if (!this.published && !LanServerSettingsApplier.publish(server, selected)) {
                        throw new IllegalStateException("Minecraft could not open the selected LAN port");
                     }

                     LanServerSettingsApplier.apply(server, selected);
                     this.minecraft.execute(() -> {
                        if (this.minecraft.getSingleplayerServer() != server) {
                           this.saving = false;
                        } else {
                           ModernLanSettings.save(selected);
                           if (this.published) {
                              ModernLanSettings.refreshSteamSession(server);
                           }

                           MinecraftUiCompat.setScreen(this.minecraft, null);
                        }
                     });
                  } catch (RuntimeException var4x) {
                     E4steamClient.LOGGER.warn("Could not apply e4steam LAN settings", var4x);
                     this.minecraft.execute(() -> {
                        this.saving = false;
                        this.done.setMessage(Mirror.translatable("text.e4steam_minecraft.lan.failed"));
                        this.validate();
                     });
                  }
               });
            }
         }
      }
   }

   private Component gameModeLabel() {
      return Mirror.translatable("gameMode." + this.draft.gameMode());
   }

   private Component accessLabel() {
      return Mirror.append(
         Mirror.append(Mirror.translatable("text.e4steam_minecraft.accessMode"), Mirror.literal(": ")), Mirror.translatable(this.access.translationKey())
      );
   }

   private Component state(String key, boolean enabled) {
      return Mirror.translatable("text.e4steam_minecraft.lan." + key, Mirror.translatable(enabled ? "options.on" : "options.off"));
   }

   public void onClose() {
      if (!this.saving) {
         MinecraftUiCompat.setScreen(this.minecraft, this.parent);
      }
   }
}
