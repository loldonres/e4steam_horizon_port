package link.e4steam.internal.kaleido.lib.quiltconfig.api.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Optional;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.metadata.MetadataType;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.metadata.NamingScheme;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.metadata.NamingSchemes;

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.TYPE})
public @interface DisplayNameConvention {
   MetadataType<NamingScheme, DisplayNameConvention.Builder> TYPE = MetadataType.create(Optional::empty, DisplayNameConvention.Builder::new, true);

   NamingSchemes value() default NamingSchemes.PASSTHROUGH;

   String custom() default "";

   public static final class Builder implements MetadataType.Builder<NamingScheme> {
      private NamingScheme scheme = NamingSchemes.PASSTHROUGH;

      public void set(NamingScheme scheme) {
         this.scheme = scheme;
      }

      public NamingScheme build() {
         return this.scheme;
      }
   }
}
