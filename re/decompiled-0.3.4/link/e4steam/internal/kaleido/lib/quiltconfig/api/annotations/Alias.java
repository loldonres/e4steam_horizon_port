package link.e4steam.internal.kaleido.lib.quiltconfig.api.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.metadata.MetadataType;
import link.e4steam.internal.kaleido.lib.quiltconfig.impl.Aliases;
import link.e4steam.internal.kaleido.lib.quiltconfig.impl.AliasesImpl;

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD})
@Repeatable(Aliases.class)
public @interface Alias {
   MetadataType<link.e4steam.internal.kaleido.lib.quiltconfig.api.metadata.Aliases, Alias.Builder> TYPE = MetadataType.create(
      () -> Optional.of(new AliasesImpl(Collections.emptyList())), Alias.Builder::new
   );

   String[] value();

   public static final class Builder implements MetadataType.Builder<link.e4steam.internal.kaleido.lib.quiltconfig.api.metadata.Aliases> {
      private final List<String> aliases = new ArrayList<>(0);

      Builder() {
      }

      public void add(String... aliases) {
         this.aliases.addAll(Arrays.asList(aliases));
      }

      public link.e4steam.internal.kaleido.lib.quiltconfig.api.metadata.Aliases build() {
         return new AliasesImpl(this.aliases);
      }
   }
}
