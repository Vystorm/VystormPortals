package at.esoren.vystormportals;

import Vystorm.vystorm_core.lang.Lang;
import org.bukkit.command.CommandSender;

/** Keeps diagnostic messages usable without Core while translating command validation for players. */
final class PortalInputException extends IllegalArgumentException {
    private final String key;
    private final Object[] arguments;
    PortalInputException(String key,String fallback,Object... arguments) {
        super(fallback);this.key=key;this.arguments=arguments;
    }
    String translated(Lang lang,CommandSender sender) {
        if(lang==null)return getMessage();
        Object[] pairs=new Object[arguments.length*2];
        for(int i=0;i<arguments.length;i++) {pairs[i*2]=Integer.toString(i);pairs[i*2+1]=arguments[i];}
        return lang.plain(sender,"errors."+key,pairs);
    }
}
