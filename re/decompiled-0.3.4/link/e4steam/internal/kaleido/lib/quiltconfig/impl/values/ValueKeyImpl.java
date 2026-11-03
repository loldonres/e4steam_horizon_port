package link.e4steam.internal.kaleido.lib.quiltconfig.impl.values;

import java.util.Arrays;
import java.util.Iterator;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.values.ValueKey;
import org.jetbrains.annotations.NotNull;

public final class ValueKeyImpl implements ValueKey {
   private final String string;
   private final String[] keys;

   public ValueKeyImpl(String[] keys) {
      if (keys.length == 0) {
         throw new IllegalArgumentException("Keys cannot be empty");
      } else {
         this.keys = new String[keys.length];
         System.arraycopy(keys, 0, this.keys, 0, keys.length);
         StringBuilder builder = new StringBuilder(this.keys[0]);

         for (int i = 0; i < this.keys.length; i++) {
            if (this.keys[i] == null) {
               throw new IllegalArgumentException("No component of a key can be null");
            }

            if (i > 0) {
               builder.append('.').append(this.keys[i]);
            }
         }

         this.string = builder.toString();
      }
   }

   public ValueKeyImpl(String key0, String... keys) {
      this.keys = new String[keys.length + 1];
      this.keys[0] = key0;
      System.arraycopy(keys, 0, this.keys, 1, keys.length);
      StringBuilder builder = new StringBuilder(key0);

      for (int i = 0; i < this.keys.length; i++) {
         if (this.keys[i] == null) {
            throw new IllegalArgumentException("No component of a key can be null");
         }

         if (i > 0) {
            builder.append('.').append(this.keys[i]);
         }
      }

      this.string = builder.toString();
   }

   @Override
   public ValueKey child(String key) {
      String[] newKey = new String[this.keys.length + 1];
      System.arraycopy(this.keys, 0, newKey, 0, this.keys.length);
      newKey[newKey.length - 1] = key;
      return new ValueKeyImpl(newKey);
   }

   @Override
   public String toString() {
      return this.string;
   }

   @Override
   public boolean equals(Object obj) {
      if (!(obj instanceof ValueKeyImpl)) {
         return false;
      } else {
         ValueKeyImpl other = (ValueKeyImpl)obj;
         if (other.length() != this.length()) {
            return false;
         } else {
            for (int i = 0; i < this.length(); i++) {
               if (!this.getKeyComponent(i).equals(other.getKeyComponent(i))) {
                  return false;
               }
            }

            return true;
         }
      }
   }

   @Override
   public int hashCode() {
      return Arrays.hashCode((Object[])this.keys);
   }

   @Override
   public int length() {
      return this.keys.length;
   }

   @Override
   public String getKeyComponent(int i) {
      return this.keys[i];
   }

   @NotNull
   @Override
   public Iterator<String> iterator() {
      return new ValueKeyImpl.Itr();
   }

   @Override
   public boolean startsWith(ValueKey key) {
      for (int i = 0; i < key.length() && i < this.length(); i++) {
         if (!key.getKeyComponent(i).equals(this.keys[i])) {
            return false;
         }
      }

      return true;
   }

   @Override
   public boolean isSibling(ValueKey key) {
      if (this.keys.length > 1 && this.keys.length == key.length()) {
         for (int i = 0; i < this.keys.length - 1; i++) {
            if (!this.keys[i].equals(key.getKeyComponent(i))) {
               return false;
            }
         }

         return true;
      } else {
         return false;
      }
   }

   @Override
   public String getLastComponent() {
      return this.keys[this.keys.length - 1];
   }

   @Override
   public ValueKey child(ValueKey key) {
      String[] newKey = new String[this.keys.length + key.length()];
      System.arraycopy(this.keys, 0, newKey, 0, this.keys.length);
      System.arraycopy(((ValueKeyImpl)key).keys, 0, newKey, this.keys.length, key.length());
      return new ValueKeyImpl(newKey);
   }

   private final class Itr implements Iterator<String> {
      private int i = 0;

      private Itr() {
      }

      @Override
      public boolean hasNext() {
         return this.i < ValueKeyImpl.this.keys.length;
      }

      public String next() {
         return ValueKeyImpl.this.keys[this.i++];
      }
   }
}
