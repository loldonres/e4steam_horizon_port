package link.e4steam.internal.kaleido.lib.nightconfig.core;

public final class NullObject {
   public static final NullObject NULL_OBJECT = new NullObject();

   private NullObject() {
   }

   @Override
   public String toString() {
      return "NULL_OBJECT";
   }

   @Override
   public boolean equals(Object o) {
      return o == this;
   }

   @Override
   public int hashCode() {
      return 0;
   }
}
