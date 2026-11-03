package link.e4steam.internal.kaleido.lib.quiltconfig.api.metadata;

import java.util.Objects;

public class ChangeWarning {
   private final String customMessage;
   private final ChangeWarning.Type type;

   public ChangeWarning(String customMessage, ChangeWarning.Type type) {
      this.customMessage = customMessage;
      this.type = type;
   }

   public String getCustomMessage() {
      return this.customMessage;
   }

   public ChangeWarning.Type getType() {
      return this.type;
   }

   @Override
   public boolean equals(Object o) {
      if (this == o) {
         return true;
      } else if (o != null && this.getClass() == o.getClass()) {
         ChangeWarning that = (ChangeWarning)o;
         return Objects.equals(this.customMessage, that.customMessage) && this.type == that.type;
      } else {
         return false;
      }
   }

   @Override
   public int hashCode() {
      return Objects.hash(this.customMessage, this.type);
   }

   public static enum Type {
      RequiresRestart,
      Unsafe,
      Experimental,
      CustomTranslatable,
      Custom;
   }
}
