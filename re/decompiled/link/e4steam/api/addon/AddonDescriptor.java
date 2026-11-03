package link.e4steam.api.addon;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import link.e4steam.api.ApiVersion;
import link.e4steam.api.ApiVersionRange;
import link.e4steam.api.capability.CapabilityId;

public final class AddonDescriptor {
   private final AddonId id;
   private final String displayName;
   private final ApiVersion version;
   private final ApiVersionRange apiRange;
   private final List<AddonDependency> dependencies;
   private final Set<CapabilityId> requestedCapabilities;
   private final Set<CapabilityId> requiredCapabilities;

   public AddonDescriptor(
      AddonId id, String displayName, ApiVersion version, ApiVersionRange apiRange, List<AddonDependency> dependencies, Set<CapabilityId> requestedCapabilities
   ) {
      this(id, displayName, version, apiRange, dependencies, requestedCapabilities, Collections.emptySet());
   }

   public AddonDescriptor(
      AddonId id,
      String displayName,
      ApiVersion version,
      ApiVersionRange apiRange,
      List<AddonDependency> dependencies,
      Set<CapabilityId> requestedCapabilities,
      Set<CapabilityId> requiredCapabilities
   ) {
      this.id = Objects.requireNonNull(id, "id");
      this.displayName = validateName(displayName);
      this.version = Objects.requireNonNull(version, "version");
      this.apiRange = Objects.requireNonNull(apiRange, "apiRange");
      this.dependencies = immutableDependencies(dependencies);
      this.requestedCapabilities = immutableCapabilities(requestedCapabilities);
      this.requiredCapabilities = immutableCapabilities(requiredCapabilities);
      if (!this.requestedCapabilities.containsAll(this.requiredCapabilities)) {
         throw new IllegalArgumentException("Required capabilities must also be requested");
      } else {
         for (AddonDependency dependency : this.dependencies) {
            if (dependency.addonId().equals(id)) {
               throw new IllegalArgumentException("Addon cannot depend on itself");
            }
         }
      }
   }

   public AddonId id() {
      return this.id;
   }

   public String displayName() {
      return this.displayName;
   }

   public ApiVersion version() {
      return this.version;
   }

   public ApiVersionRange apiRange() {
      return this.apiRange;
   }

   public List<AddonDependency> dependencies() {
      return this.dependencies;
   }

   public Set<CapabilityId> requestedCapabilities() {
      return this.requestedCapabilities;
   }

   public Set<CapabilityId> requiredCapabilities() {
      return this.requiredCapabilities;
   }

   @Override
   public String toString() {
      return "AddonDescriptor{id=" + this.id + ", version=" + this.version + '}';
   }

   private static String validateName(String name) {
      if (name == null) {
         throw new NullPointerException("displayName");
      } else {
         String checked = name.trim();
         if (!checked.isEmpty() && checked.length() <= 64) {
            return checked;
         } else {
            throw new IllegalArgumentException("Addon display name has an invalid length");
         }
      }
   }

   private static List<AddonDependency> immutableDependencies(List<AddonDependency> dependencies) {
      if (dependencies == null) {
         throw new NullPointerException("dependencies");
      } else if (dependencies.size() > 64) {
         throw new IllegalArgumentException("Too many addon dependencies");
      } else {
         ArrayList<AddonDependency> copy = new ArrayList<>(dependencies.size());
         LinkedHashSet<AddonId> ids = new LinkedHashSet<>();

         for (AddonDependency dependency : dependencies) {
            Objects.requireNonNull(dependency, "dependency");
            if (!ids.add(dependency.addonId())) {
               throw new IllegalArgumentException("Duplicate addon dependency");
            }

            copy.add(dependency);
         }

         return Collections.unmodifiableList(copy);
      }
   }

   private static Set<CapabilityId> immutableCapabilities(Set<CapabilityId> capabilities) {
      if (capabilities == null) {
         throw new NullPointerException("requestedCapabilities");
      } else if (capabilities.size() > 64) {
         throw new IllegalArgumentException("Too many requested capabilities");
      } else {
         LinkedHashSet<CapabilityId> copy = new LinkedHashSet<>();

         for (CapabilityId capability : capabilities) {
            copy.add(Objects.requireNonNull(capability, "capability"));
         }

         return Collections.unmodifiableSet(copy);
      }
   }
}
