package link.e4steam.internal.kaleido.lib.quiltconfig.api.values;

import org.jetbrains.annotations.ApiStatus.NonExtendable;

@NonExtendable
public interface ValueKey extends Iterable<String> {
   int length();

   String getKeyComponent(int var1);

   ValueKey child(String var1);

   boolean startsWith(ValueKey var1);

   boolean isSibling(ValueKey var1);

   String getLastComponent();

   ValueKey child(ValueKey var1);
}
