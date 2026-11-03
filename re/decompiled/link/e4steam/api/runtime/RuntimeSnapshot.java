package link.e4steam.api.runtime;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import link.e4steam.api.ApiVersion;

public final class RuntimeSnapshot {
   private final ApiVersion apiVersion;
   private final String modVersion;
   private final int wireVersion;
   private final Platform platform;
   private final Architecture architecture;
   private final RuntimeMode runtimeMode;
   private final LoaderInfo loader;
   private final String minecraftVersion;
   private final SteamRuntimeState steamState;
   private final LifecyclePhase lifecyclePhase;
   private final Set<TransportCapability> transports;
   private final Set<CompatibilityFlag> compatibilityFlags;
   private final String lastFailureCategory;

   public RuntimeSnapshot(
      ApiVersion apiVersion,
      String modVersion,
      int wireVersion,
      Platform platform,
      Architecture architecture,
      RuntimeMode runtimeMode,
      LoaderInfo loader,
      String minecraftVersion,
      SteamRuntimeState steamState,
      LifecyclePhase lifecyclePhase,
      Set<TransportCapability> transports,
      Set<CompatibilityFlag> compatibilityFlags,
      String lastFailureCategory
   ) {
      this.apiVersion = Objects.requireNonNull(apiVersion, "apiVersion");
      this.modVersion = safe(modVersion, "modVersion", 64, false);
      if (wireVersion < 0) {
         throw new IllegalArgumentException("wireVersion must be non-negative");
      } else {
         this.wireVersion = wireVersion;
         this.platform = Objects.requireNonNull(platform, "platform");
         this.architecture = Objects.requireNonNull(architecture, "architecture");
         this.runtimeMode = Objects.requireNonNull(runtimeMode, "runtimeMode");
         this.loader = Objects.requireNonNull(loader, "loader");
         this.minecraftVersion = safe(minecraftVersion, "minecraftVersion", 64, false);
         this.steamState = Objects.requireNonNull(steamState, "steamState");
         this.lifecyclePhase = Objects.requireNonNull(lifecyclePhase, "lifecyclePhase");
         this.transports = immutableEnumSet(transports, TransportCapability.class);
         this.compatibilityFlags = immutableEnumSet(compatibilityFlags, CompatibilityFlag.class);
         this.lastFailureCategory = safe(lastFailureCategory, "lastFailureCategory", 64, true);
      }
   }

   public ApiVersion apiVersion() {
      return this.apiVersion;
   }

   public String modVersion() {
      return this.modVersion;
   }

   public int wireVersion() {
      return this.wireVersion;
   }

   public Platform platform() {
      return this.platform;
   }

   public Architecture architecture() {
      return this.architecture;
   }

   public RuntimeMode runtimeMode() {
      return this.runtimeMode;
   }

   public LoaderInfo loader() {
      return this.loader;
   }

   public String minecraftVersion() {
      return this.minecraftVersion;
   }

   public SteamRuntimeState steamState() {
      return this.steamState;
   }

   public LifecyclePhase lifecyclePhase() {
      return this.lifecyclePhase;
   }

   public Set<TransportCapability> transports() {
      return this.transports;
   }

   public Set<CompatibilityFlag> compatibilityFlags() {
      return this.compatibilityFlags;
   }

   public String lastFailureCategory() {
      return this.lastFailureCategory;
   }

   @Override
   public String toString() {
      return "RuntimeSnapshot{api="
         + this.apiVersion
         + ", mod='"
         + this.modVersion
         + '\''
         + ", wire="
         + this.wireVersion
         + ", platform="
         + this.platform
         + ", architecture="
         + this.architecture
         + ", mode="
         + this.runtimeMode
         + ", loader="
         + this.loader
         + ", minecraft='"
         + this.minecraftVersion
         + '\''
         + ", steam="
         + this.steamState
         + ", phase="
         + this.lifecyclePhase
         + '}';
   }

   private static String safe(String value, String field, int maximum, boolean optional) {
      if (value == null) {
         throw new NullPointerException(field);
      } else {
         String checked = value.trim();
         if ((optional || !checked.isEmpty()) && checked.length() <= maximum) {
            return checked;
         } else {
            throw new IllegalArgumentException(field + " has an invalid length");
         }
      }
   }

   private static <E extends Enum<E>> Set<E> immutableEnumSet(Set<E> values, Class<E> type) {
      Objects.requireNonNull(values, "values");
      if (values.isEmpty()) {
         return Collections.emptySet();
      } else {
         EnumSet<E> copy = EnumSet.noneOf(type);
         copy.addAll(values);
         return Collections.unmodifiableSet(copy);
      }
   }
}
