package link.e4steam.internal.kaleido.lib.quiltconfig.impl;

import java.security.InvalidParameterException;
import java.util.List;

public final class AliasesImpl extends StringIterator implements link.e4steam.internal.kaleido.lib.quiltconfig.api.metadata.Aliases {
   public AliasesImpl(List<String> aliases) {
      super(aliases);

      for (String alias : aliases) {
         if (alias.contains(" ")) {
            throw new InvalidParameterException("Cannot create an alias that contains a space!");
         }
      }
   }
}
