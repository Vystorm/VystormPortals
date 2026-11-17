package at.esoren.vystormportals;

import Vystorm.vystorm_core.lang.Lang;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Menu icons with name and lore from the language files, never italic. */
final class MenuItems {
    private MenuItems() {}
    static ItemStack item(Vystorm.vystorm_core.compat.PlatformCompatInterface platform,Lang lang,Player viewer,Material material,String nameKey,String loreKey,Object... placeholders) {
        ItemStack item=new ItemStack(material);
        platform.editMeta(item, meta->{
            platform.displayName(meta, lang.component(viewer,nameKey,placeholders).decorationIfAbsent(TextDecoration.ITALIC,TextDecoration.State.FALSE));
            if(loreKey!=null) platform.lore(meta, lang.lines(viewer,loreKey,placeholders).stream()
                    .map(line->line.decorationIfAbsent(TextDecoration.ITALIC,TextDecoration.State.FALSE)).toList());
        });
        return item;
    }
}
