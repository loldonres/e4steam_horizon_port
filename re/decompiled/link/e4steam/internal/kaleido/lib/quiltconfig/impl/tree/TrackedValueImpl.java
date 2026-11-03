package link.e4steam.internal.kaleido.lib.quiltconfig.impl.tree;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Map.Entry;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.Constraint;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.exceptions.TrackedValueException;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.metadata.MetadataType;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.values.ComplexConfigValue;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.values.TrackedValue;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.values.ValueKey;
import link.e4steam.internal.kaleido.lib.quiltconfig.impl.AbstractMetadataContainer;
import link.e4steam.internal.kaleido.lib.quiltconfig.impl.ConfigImpl;
import link.e4steam.internal.kaleido.lib.quiltconfig.impl.util.ImmutableIterable;
import org.jetbrains.annotations.NotNull;

public final class TrackedValueImpl<T> extends AbstractMetadataContainer implements TrackedValue<T> {
   public List<TrackedValue.UpdateCallback<T>> callbacks;
   public List<Constraint<T>> constraints;
   private final T defaultValue;
   private ValueKey key;
   private ConfigImpl config;
   private T value;
   private boolean isBeingOverridden = false;
   private T valueOverride;

   public TrackedValueImpl(
      ValueKey key, T defaultValue, Map<MetadataType<?, ?>, Object> metadata, List<TrackedValue.UpdateCallback<T>> callbacks, List<Constraint<T>> constraints
   ) {
      super(metadata);
      this.key = key;
      this.defaultValue = defaultValue;
      this.value = defaultValue;
      this.callbacks = callbacks;
      this.constraints = constraints;
      this.assertValue(defaultValue);
      if (defaultValue instanceof ComplexConfigValue) {
         ((ComplexConfigValue)defaultValue).setValue(this);
         this.value = (T)((ComplexConfigValue)this.defaultValue).copy();
      } else {
         this.value = defaultValue;
      }
   }

   public void setConfig(ConfigImpl config) {
      if (this.config != null) {
         throw new TrackedValueException("TrackedValue '" + this.key + "' cannot be assigned to multiple configs");
      } else {
         this.config = config;
      }
   }

   public TrackedValueImpl<T> setKey(ValueKey key) {
      this.key = key;
      return this;
   }

   @Override
   public ValueKey key() {
      return this.key;
   }

   @Override
   public void propagateInheritedMetadata(Map<MetadataType<?, ?>, Object> inheritedMetadata) {
      for (Entry<MetadataType<?, ?>, Object> entry : inheritedMetadata.entrySet()) {
         this.metadata.putIfAbsent(entry.getKey(), entry.getValue());
      }
   }

   @Override
   public T value() {
      return this.isBeingOverridden ? this.valueOverride : this.value;
   }

   @Override
   public boolean isBeingOverridden() {
      return this.isBeingOverridden;
   }

   @Override
   public T getRealValue() {
      return this.value;
   }

   private void assertValue(T value) {
      Optional<Iterable<String>> errors = this.checkForFailingConstraints(value);
      if (errors.isPresent()) {
         StringBuilder errorMessage = new StringBuilder();

         for (String message : errors.get()) {
            errorMessage.append(message).append('\n');
         }

         throw new TrackedValueException(errorMessage.toString());
      }
   }

   @Override
   public T setValue(@NotNull T newValue, boolean serialize) {
      this.assertValue(newValue);
      if (newValue instanceof ComplexConfigValue) {
         ((ComplexConfigValue)newValue).setValue(this);
      }

      T oldValue = this.value;
      this.value = newValue;
      if (serialize) {
         this.config.save();
      }

      if (!this.isBeingOverridden()) {
         this.invokeCallbacks();
      }

      return oldValue;
   }

   @Override
   public void setOverride(T newValue) {
      this.assertValue(newValue);
      this.isBeingOverridden = true;
      this.valueOverride = newValue;
      this.invokeCallbacks();
   }

   @Override
   public void removeOverride() {
      this.isBeingOverridden = false;
      this.valueOverride = null;
      this.invokeCallbacks();
   }

   @Override
   public T getDefaultValue() {
      return this.defaultValue;
   }

   @Override
   public void registerCallback(TrackedValue.UpdateCallback<T> callback) {
      this.callbacks.add(callback);
   }

   @Override
   public Iterable<Constraint<T>> constraints() {
      return new ImmutableIterable<>(this.constraints);
   }

   @Override
   public Optional<Iterable<String>> checkForFailingConstraints(T value) {
      List<String> failing = null;
      if (value == null) {
         failing = new ArrayList<>();
         failing.add("Value cannot be null");
      }

      for (Constraint<T> constraint : this.constraints) {
         Optional<String> errorMessage = constraint.test(value);
         if (errorMessage.isPresent()) {
            if (failing == null) {
               failing = new ArrayList<>();
            }

            failing.add(errorMessage.get());
         }
      }

      return failing == null ? Optional.empty() : Optional.of(new ImmutableIterable<>(failing));
   }

   @Override
   public void invokeCallbacks() {
      this.config.invokeCallbacks();

      for (TrackedValue.UpdateCallback<T> callback : this.callbacks) {
         callback.onUpdate(this);
      }
   }

   @Override
   public void serializeAndInvokeCallbacks() {
      this.config.save();
      this.config.invokeCallbacks();

      for (TrackedValue.UpdateCallback<T> callback : this.callbacks) {
         callback.onUpdate(this);
      }
   }

   @Override
   public String toString() {
      return String.format("TrackedValueImpl[%s]", this.value.toString());
   }
}
