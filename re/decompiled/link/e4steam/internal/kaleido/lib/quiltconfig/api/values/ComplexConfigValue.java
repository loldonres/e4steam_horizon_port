package link.e4steam.internal.kaleido.lib.quiltconfig.api.values;

public interface ComplexConfigValue {
   void setValue(TrackedValue<?> var1);

   ComplexConfigValue copy();
}
