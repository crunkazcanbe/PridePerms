package com.dogpound.prideperms.mixin;

import com.dogpound.prideperms.PermsConfig;
import com.dogpound.prideperms.PridePerms;
import net.minecraft.entity.player.EntityPlayerMP;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Every "may this player use command X?" in Minecraft and most mods ends here (CommandBase.checkPermission →
 * canUseCommand). A rule command.<name> = true GIVES the command without op; = false takes it away even from ops.
 * No rule = vanilla's op check, unchanged.
 */
@Mixin(EntityPlayerMP.class)
public abstract class MixinCanUseCommand {
    @Inject(method = "func_70003_b", at = @At("HEAD"), cancellable = true, remap = false)
    private void prideperms$ask(int level, String command, CallbackInfoReturnable<Boolean> cir) {
        if (command == null || command.isEmpty()) return;
        Boolean d = PridePerms.decideCommand(((EntityPlayerMP) (Object) this).getUniqueID(), "command." + command.toLowerCase(java.util.Locale.ROOT).replace('.', '_'));
        if (d == null) return;                                  // no rule: vanilla's op check
        if (!d) cir.setReturnValue(false);                      // taken away
        else if (PermsConfig.grantCommands) cir.setReturnValue(true);   // given
    }
}
