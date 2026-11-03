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
public @interface SerializedNameConvention {
   MetadataType<NamingScheme, SerializedNameConvention.Builder> TYPE = MetadataType.create(Optional::empty, SerializedNameConvention.Builder::new, true);

   NamingSchemes value() default NamingSchemes.PASSTHROUGH;

   String custom() default "";

   public static final class Builder implements MetadataType.Builder<NamingScheme> {
      private NamingScheme scheme = NamingSchemes.PASSTHROUGH;

      Builder() {
      }

      public void set(NamingScheme scheme) {
         if (scheme != NamingSchemes.SPACE_SEPARATED_LOWER_CASE
            && scheme != NamingSchemes.SPACE_SEPARATED_LOWER_CASE_INITIAL_UPPER_CASE
            && scheme != NamingSchemes.TITLE_CASE) {
            this.scheme = scheme;
         } else {
            throw new IllegalArgumentException("Scheme with spaces unsupported for serialized names");
         }
      }

      public NamingScheme build() {
         return this.scheme;
      }
   }
}
