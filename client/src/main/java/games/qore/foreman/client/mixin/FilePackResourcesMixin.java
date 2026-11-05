package games.qore.foreman.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import games.qore.fastload.Claims;
import games.qore.fastload.ZipIndex;
import games.qore.foreman.client.reload.FastReload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.FilePackResources;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.IoSupplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import java.util.zip.ZipFile;

/** A zip pack lists a folder from an index of its files (fastload's ZipIndex), with the same results in the same order. */
@Mixin(FilePackResources.class)
public abstract class FilePackResourcesMixin {
    @Shadow @Final private static Logger LOGGER;

    @Shadow protected abstract String addPrefix(String path);

    /** Lists the folder from the index, then gives vanilla no zip, which it then doesn't go through. */
    @ModifyExpressionValue(method = "listResources", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/server/packs/FilePackResources$SharedZipFileAccess;getOrCreateZipFile()Ljava/util/zip/ZipFile;"))
    private @Nullable ZipFile foreman$listIndexed(@Nullable ZipFile zipFile, @Local(argsOnly = true) PackType type,
                                                  @Local(argsOnly = true, ordinal = 0) String namespace, @Local(argsOnly = true, ordinal = 1) String directory,
                                                  @Local(argsOnly = true) PackResources.ResourceOutput output) {
        if (zipFile == null || !FastReload.handles(Claims.ZIP_INDEX)) return zipFile;
        String root = addPrefix(type.getDirectory() + "/" + namespace + "/");
        ZipIndex.cached(zipFile).forEachStartingWith(root + directory + "/", zipEntry -> {
            String path = zipEntry.getName().substring(root.length());
            Identifier id = Identifier.tryBuild(namespace, path);
            if (id != null) output.accept(id, IoSupplier.create(zipFile, zipEntry));
            else LOGGER.warn("Invalid path in datapack: {}:{}, ignoring", namespace, path);
        });
        return null;
    }
}
