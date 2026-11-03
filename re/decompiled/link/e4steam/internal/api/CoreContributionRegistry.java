package link.e4steam.internal.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import link.e4steam.api.ApiErrorCode;
import link.e4steam.api.ApiResult;
import link.e4steam.api.Registration;
import link.e4steam.api.ResourceScope;
import link.e4steam.api.addon.AddonId;

final class CoreContributionRegistry {
   private final Object lock = new Object();
   private final Map<String, LinkedHashMap<String, CoreContributionRegistry.Entry>> families = new HashMap<>();
   private volatile BooleanSupplier frozen = () -> false;

   void frozenSupplier(BooleanSupplier supplier) {
      this.frozen = supplier;
   }

   boolean isFrozen() {
      return this.frozen.getAsBoolean();
   }

   ApiResult<Registration> register(String family, String id, AddonId owner, Object value, ResourceScope resources, boolean freezeSensitive) {
      if (family == null || id == null || owner == null || value == null || resources == null) {
         return SafeApiErrors.failure(ApiErrorCode.INVALID_ARGUMENT, "contribution.register", "Validation");
      } else if (freezeSensitive && this.frozen.getAsBoolean()) {
         return SafeApiErrors.failure(ApiErrorCode.UNAVAILABLE, "contribution.register", "RegistrationsFrozen");
      } else {
         String expected = owner.value().replace(':', '-').toLowerCase(Locale.ROOT) + ":";
         if (!id.toLowerCase(Locale.ROOT).startsWith(expected)) {
            return SafeApiErrors.failure(ApiErrorCode.SECURITY_REJECTION, "contribution.namespace", "WrongOwnerNamespace");
         } else {
            CoreRegistration registration;
            synchronized (this.lock) {
               LinkedHashMap<String, CoreContributionRegistry.Entry> entries = this.families.computeIfAbsent(family, ignored -> new LinkedHashMap<>());
               if (entries.size() >= 1000) {
                  return SafeApiErrors.failure(ApiErrorCode.QUEUE_FULL, "contribution.register", "RegistrationLimit");
               }

               if (entries.containsKey(id)) {
                  return SafeApiErrors.failure(ApiErrorCode.INVALID_ARGUMENT, "contribution.register", "DuplicateId");
               }

               registration = new CoreRegistration(() -> this.remove(family, id, owner));
               entries.put(id, new CoreContributionRegistry.Entry(owner, value, registration));
            }

            resources.own(registration);
            return ApiResult.success(registration);
         }
      }
   }

   Object find(String family, String id) {
      CoreContributionRegistry.OwnedContribution contribution = this.findOwned(family, id);
      return contribution == null ? null : contribution.value();
   }

   CoreContributionRegistry.OwnedContribution findOwned(String family, String id) {
      synchronized (this.lock) {
         Map<String, CoreContributionRegistry.Entry> entries = this.families.get(family);
         CoreContributionRegistry.Entry entry = entries == null ? null : entries.get(id);
         return entry != null && !entry.registration.isClosed() ? new CoreContributionRegistry.OwnedContribution(entry.owner, entry.value) : null;
      }
   }

   int size(String family) {
      synchronized (this.lock) {
         Map<String, CoreContributionRegistry.Entry> entries = this.families.get(family);
         return entries == null ? 0 : entries.size();
      }
   }

   List<Object> snapshotValues(String family) {
      synchronized (this.lock) {
         Map<String, CoreContributionRegistry.Entry> entries = this.families.get(family);
         if (entries != null && !entries.isEmpty()) {
            ArrayList<Object> values = new ArrayList<>(entries.size());

            for (CoreContributionRegistry.Entry entry : entries.values()) {
               if (!entry.registration.isClosed()) {
                  values.add(entry.value);
               }
            }

            return Collections.unmodifiableList(values);
         } else {
            return Collections.emptyList();
         }
      }
   }

   private void remove(String family, String id, AddonId owner) {
      synchronized (this.lock) {
         Map<String, CoreContributionRegistry.Entry> entries = this.families.get(family);
         CoreContributionRegistry.Entry entry = entries == null ? null : entries.get(id);
         if (entry != null && entry.owner.equals(owner)) {
            entries.remove(id);
         }
      }
   }

   private static final class Entry {
      private final AddonId owner;
      private final Object value;
      private final CoreRegistration registration;

      private Entry(AddonId owner, Object value, CoreRegistration registration) {
         this.owner = owner;
         this.value = value;
         this.registration = registration;
      }
   }

   static final class OwnedContribution {
      private final AddonId owner;
      private final Object value;

      private OwnedContribution(AddonId owner, Object value) {
         this.owner = owner;
         this.value = value;
      }

      AddonId owner() {
         return this.owner;
      }

      Object value() {
         return this.value;
      }
   }
}
