package link.e4steam.internal.kaleido.lib.quiltconfig.impl.values;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Spliterator;
import java.util.function.UnaryOperator;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.values.ComplexConfigValue;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.values.CompoundConfigValue;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.values.TrackedValue;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.values.ValueList;
import link.e4steam.internal.kaleido.lib.quiltconfig.impl.tree.TrackedValueImpl;
import org.jetbrains.annotations.NotNull;

public final class ValueListImpl<T> implements ValueList<T>, CompoundConfigValue<T> {
   private final T defaultValue;
   private final List<T> values;
   private TrackedValueImpl<?> configValue;

   public ValueListImpl(T defaultValue, List<T> values) {
      this.defaultValue = defaultValue;
      this.values = values;
   }

   public ValueList<T> copy() {
      List<T> values = new ArrayList<>(this.values.size());

      for (T value : this.values) {
         if (value instanceof CompoundConfigValue) {
            values.add((T)((CompoundConfigValue)value).copy());
         } else {
            values.add(value);
         }
      }

      ValueListImpl<T> result = new ValueListImpl<>(this.defaultValue, values);
      result.setValue(this.configValue);
      return result;
   }

   @Override
   public void setValue(TrackedValue<?> configValue) {
      this.configValue = (TrackedValueImpl<?>)configValue;
      if (this.defaultValue instanceof ComplexConfigValue) {
         for (T value : this.values) {
            ((ComplexConfigValue)value).setValue(configValue);
         }
      }
   }

   @Override
   public Class<T> getType() {
      return (Class<T>)this.defaultValue.getClass();
   }

   @Override
   public T getDefaultValue() {
      return this.defaultValue;
   }

   @Override
   public Iterable<T> values() {
      return this;
   }

   @Override
   public int size() {
      return this.values.size();
   }

   @Override
   public boolean isEmpty() {
      return this.values.isEmpty();
   }

   @Override
   public boolean contains(Object o) {
      return this.values.contains(o);
   }

   @NotNull
   @Override
   public Iterator<T> iterator() {
      return this.values.iterator();
   }

   @NotNull
   @Override
   public Object[] toArray() {
      return this.values.toArray();
   }

   @NotNull
   @Override
   public <T1> T1[] toArray(@NotNull T1[] a) {
      return this.values.toArray(a);
   }

   @Override
   public boolean add(T t) {
      this.values.add(t);
      this.configValue.serializeAndInvokeCallbacks();
      return true;
   }

   @Override
   public boolean remove(Object o) {
      boolean r = this.values.remove(o);
      if (r) {
         this.configValue.serializeAndInvokeCallbacks();
      }

      return r;
   }

   @Override
   public boolean containsAll(@NotNull Collection<?> c) {
      return new HashSet<>(this.values).containsAll(c);
   }

   @Override
   public boolean addAll(@NotNull Collection<? extends T> c) {
      boolean v = this.values.addAll(c);
      if (v) {
         this.configValue.serializeAndInvokeCallbacks();
      }

      return v;
   }

   @Override
   public boolean addAll(int index, @NotNull Collection<? extends T> c) {
      boolean v = this.values.addAll(index, c);
      if (v) {
         this.configValue.serializeAndInvokeCallbacks();
      }

      return v;
   }

   @Override
   public boolean removeAll(@NotNull Collection<?> c) {
      boolean v = this.values.removeAll(c);
      if (v) {
         this.configValue.serializeAndInvokeCallbacks();
      }

      return v;
   }

   @Override
   public boolean retainAll(@NotNull Collection<?> c) {
      boolean v = this.values.retainAll(c);
      if (v) {
         this.configValue.serializeAndInvokeCallbacks();
      }

      return v;
   }

   @Override
   public void replaceAll(UnaryOperator<T> operator) {
      this.values.replaceAll(operator);
      this.configValue.serializeAndInvokeCallbacks();
   }

   @Override
   public void sort(Comparator<? super T> c) {
      this.values.sort(c);
      this.configValue.serializeAndInvokeCallbacks();
   }

   @Override
   public void clear() {
      if (this.isEmpty()) {
         this.values.clear();
      } else {
         this.values.clear();
         this.configValue.serializeAndInvokeCallbacks();
      }
   }

   @Override
   public boolean equals(Object o) {
      if (!(o instanceof ValueListImpl)) {
         return false;
      } else {
         ValueListImpl<?> a = (ValueListImpl<?>)o;
         return a.defaultValue.equals(this.defaultValue) && a.values.equals(this.values);
      }
   }

   @Override
   public int hashCode() {
      return this.values.hashCode();
   }

   @Override
   public T get(int index) {
      return this.values.get(index);
   }

   @Override
   public T set(int index, T value) {
      T v = this.values.set(index, value);
      if (value instanceof ComplexConfigValue) {
         ((ComplexConfigValue)value).setValue(this.configValue);
      }

      if (v != null && value != null && !v.equals(value) || v != null && value == null || v == null && value != null) {
         this.configValue.serializeAndInvokeCallbacks();
      }

      return v;
   }

   @Override
   public void add(int index, T value) {
      this.values.add(index, value);
      this.configValue.serializeAndInvokeCallbacks();
   }

   @Override
   public T remove(int index) {
      T v = this.values.remove(index);
      this.configValue.serializeAndInvokeCallbacks();
      return v;
   }

   @Override
   public int indexOf(Object o) {
      return this.values.indexOf(o);
   }

   @Override
   public int lastIndexOf(Object o) {
      return this.values.lastIndexOf(o);
   }

   @NotNull
   @Override
   public ListIterator<T> listIterator() {
      return this.values.listIterator();
   }

   @NotNull
   @Override
   public ListIterator<T> listIterator(int index) {
      return this.values.listIterator(index);
   }

   @NotNull
   @Override
   public List<T> subList(int fromIndex, int toIndex) {
      return this.values.subList(fromIndex, toIndex);
   }

   @Override
   public Spliterator<T> spliterator() {
      return this.values.spliterator();
   }

   @Override
   public void grow() {
      if (this.defaultValue instanceof ValueListImpl) {
         this.values.add((T)((ValueListImpl)this.defaultValue).copy());
      } else if (this.defaultValue instanceof ValueMapImpl) {
         this.values.add((T)((ValueMapImpl)this.defaultValue).copy());
      } else {
         this.values.add(this.defaultValue);
      }
   }
}
