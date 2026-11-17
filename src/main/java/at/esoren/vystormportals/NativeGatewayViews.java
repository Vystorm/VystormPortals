package at.esoren.vystormportals;

import Vystorm.vystorm_core.visual.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.EndGateway;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Visual-only End gateway support: the gateway block becomes a one-block window onto its exit. Minecraft keeps the
 * teleport; a gateway whose exit is still unknown (never used yet) shows no view.
 */
final class NativeGatewayViews implements AutoCloseable {
    /** A gateway with a known exit, seen from one side. */
    record Sight(ViewFrame entry, ViewFrame exit, Set<BlockPosition> cell) {}
    private record Search(long expires, BlockPosition gateway) {}
    static final int RANGE = 4;
    private final JavaPlugin plugin;
    private final DestinationChunks chunks;
    private final Map<UUID,PortalPreview> views=new HashMap<>();
    private final Map<UUID,Search> searches=new HashMap<>();
    private final Vystorm.vystorm_core.API core;
    NativeGatewayViews(JavaPlugin plugin,Vystorm.vystorm_core.API core,DestinationChunks chunks) {this.plugin=plugin;this.core=core;this.chunks=chunks;}

    boolean update(Player player,long tick) {
        UUID id=player.getUniqueId();
        if(!plugin.getConfig().getBoolean("end-portals.enabled",true) || !plugin.getConfig().getBoolean("end-portals.gateways",true)
                || !player.hasPermission("vystormportals.use") || !player.hasPermission("vystormportals.end-view") || player.isDead()) {
            close(id);return false;
        }
        Search cached=searches.get(id);
        if(cached==null || tick>=cached.expires) {cached=new Search(tick+20,find(player));searches.put(id,cached);}
        World world=player.getWorld();
        if(cached.gateway==null || !gateway(world,cached.gateway)) {stopView(id);return false;}
        Location exit=exit(world.getBlockAt(cached.gateway.x(),cached.gateway.y(),cached.gateway.z()));
        if(exit==null) {stopView(id);return false;}
        Point3 eye=point(player.getEyeLocation());
        Sight sight=sight(world.getUID(),cached.gateway,eye,exit.getWorld().getUID(),point(exit));
        if(sight==null) {stopView(id);return false;}
        chunks.touch(sight.exit.world(),sight.exit.origin(),tick,plugin.getConfig().getBoolean("end-portals.generate-destination-chunks",true));
        PortalPreview view=views.get(id);
        if(view==null || view.isClosed()) {
            try {view=new PortalPreview(core,plugin,player);views.put(id,view);}
            catch(IllegalStateException busy) {return false;}
        }
        view.upright(sight.entry,sight.exit,eye,sight.cell,tick);
        return true;
    }

    /**
     * The window faces the side the eye is on (dominant horizontal axis). The exit frame is turned half way round so
     * the view keeps the looking direction, as the gateway keeps the player's yaw. Null when the eye is too far, above
     * or below the block.
     */
    static Sight sight(UUID world,BlockPosition gateway,Point3 eye,UUID exitWorld,Point3 exit) {
        double dx=eye.x()-(gateway.x()+.5),dz=eye.z()-(gateway.z()+.5);
        if(dx*dx+dz*dz>RANGE*RANGE || Math.abs(dx)<.5 && Math.abs(dz)<.5) return null;
        if(eye.y()<gateway.y()-1 || eye.y()>gateway.y()+2) return null;
        int turns=Math.abs(dz)>=Math.abs(dx) ? (dz>0?0:2) : (dx<0?1:3);
        ViewFrame entry=new ViewFrame(world,new Point3(gateway.x()+.5,gateway.y(),gateway.z()+.5),turns,.5,1);
        ViewFrame target=new ViewFrame(exitWorld,new Point3(Math.floor(exit.x())+.5,Math.floor(exit.y()),Math.floor(exit.z())+.5),turns+2,.5,1);
        return new Sight(entry,target,Set.of(gateway));
    }

    private Location exit(Block block) {
        if(!(core.compat().platform().getState(block, false) instanceof EndGateway state)) return null;
        Location exit=state.getExitLocation();
        if(exit==null) return null;
        if(exit.getWorld()==null) exit.setWorld(block.getWorld());
        return exit;
    }
    private static BlockPosition find(Player player) {
        World world=player.getWorld();Location at=player.getEyeLocation();BlockPosition nearest=null;double best=Double.MAX_VALUE;
        for(int x=-RANGE;x<=RANGE;x++)for(int z=-RANGE;z<=RANGE;z++)for(int y=-2;y<=1;y++) {
            BlockPosition p=new BlockPosition(at.getBlockX()+x,at.getBlockY()+y,at.getBlockZ()+z);
            if(!gateway(world,p))continue;
            double distance=p.centre().distanceSquared(point(at));if(distance<best) {best=distance;nearest=p;}
        }
        return nearest;
    }
    static boolean gateway(World world,BlockPosition p) {
        return p.y()>=world.getMinHeight() && p.y()<world.getMaxHeight() && world.isChunkLoaded(p.x()>>4,p.z()>>4)
                && world.getBlockAt(p.x(),p.y(),p.z()).getType()==Material.END_GATEWAY;
    }
    private static Point3 point(Location location) {return new Point3(location.getX(),location.getY(),location.getZ());}
    boolean covers(UUID player,BlockPosition position) {PortalPreview view=views.get(player);return view!=null&&view.covers(position);}
    private void stopView(UUID player) {PortalPreview view=views.remove(player);if(view!=null)view.close();}
    void close(UUID player) {stopView(player);searches.remove(player);}
    @Override public void close() {for(UUID id:List.copyOf(views.keySet()))stopView(id);searches.clear();}
}
