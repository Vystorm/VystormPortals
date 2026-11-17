package at.esoren.vystormportals;

import Vystorm.vystorm_core.visual.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.*;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

/**
 * Non-player entities (items, projectiles, mobs, vehicles, TNT) crossing a public manual portal arrive at the linked
 * portal with their momentum rotated like a player's. Only loaded destinations; nothing is force-loaded here.
 */
final class EntityTravel {
    record Options(boolean enabled,boolean objects,boolean projectiles,boolean mobs,boolean vehicles,int perTick) {
        static Options read(ConfigurationSection c) {
            return new Options(c.getBoolean("entity-travel.enabled",true),c.getBoolean("entity-travel.objects",true),
                    c.getBoolean("entity-travel.projectiles",true),c.getBoolean("entity-travel.mobs",true),
                    c.getBoolean("entity-travel.vehicles",true),Math.clamp(c.getInt("entity-travel.max-per-tick",16),1,256));
        }
    }
    enum Kind { OBJECT, PROJECTILE, MOB, VEHICLE }
    /** Exit point and momentum at the destination. */
    record Exit(Point3 position,Point3 velocity,Point3 facing) {}
    private record Seen(UUID world,Point3 at) {}
    private final PortalsPlugin plugin;
    private final Options options;
    private Map<UUID,Seen> previous=new HashMap<>();
    private final Map<UUID,Long> cooldown=new HashMap<>();
    EntityTravel(PortalsPlugin plugin,Options options){this.plugin=plugin;this.options=options;}

    /** Which travel group an entity belongs to, or null when it never travels by itself. */
    static Kind kind(Entity entity) {
        if(entity instanceof Player || entity instanceof FishHook || entity instanceof EnderDragon || entity instanceof Wither
                || entity instanceof EnderDragonPart || entity instanceof ComplexEntityPart)return null;
        if(entity instanceof Item || entity instanceof ExperienceOrb || entity instanceof TNTPrimed || entity instanceof FallingBlock)return Kind.OBJECT;
        if(entity instanceof Projectile)return Kind.PROJECTILE;
        if(entity instanceof Vehicle && !(entity instanceof LivingEntity))return Kind.VEHICLE;
        if(entity instanceof Mob)return Kind.MOB;
        return null;
    }
    boolean allows(Kind kind) {
        return kind!=null && switch(kind) {
            case OBJECT -> options.objects(); case PROJECTILE -> options.projectiles();
            case MOB -> options.mobs(); case VEHICLE -> options.vehicles();
        };
    }

    /** Same mapping as player travel: mirrored across the aperture, kept inside the destination frame. */
    static Exit exit(ViewFrame entry,ViewFrame exit,Point3 from,Point3 velocity,double entityHeight) {
        Point3 local=entry.local(from);
        double top=Math.max(.05,exit.height()-entityHeight);
        double x=Math.clamp(-local.x(),-exit.halfWidth()+.2,exit.halfWidth()-.2);
        Point3 position=exit.global(new Point3(x,Math.clamp(local.y(),.05,top),.6));
        Point3 moved=entry.directionTo(exit,velocity);
        Point3 facing=entry.directionTo(exit,new Point3(0,0,1));
        return new Exit(position,moved,facing);
    }

    void tick(long tick,Collection<Portal> portals) {
        if(!options.enabled())return;
        Map<UUID,Seen> next=new HashMap<>();
        int moved=0;
        for(Portal portal:portals) {
            Portal destination=plugin.destination(portal);
            if(!plugin.routeOpen(portal,destination) || !portal.publicAccess() || !destination.publicAccess())continue;
            World world=Bukkit.getWorld(portal.frame().world());
            Point3 origin=portal.frame().origin();
            if(world==null || !world.isChunkLoaded((int)Math.floor(origin.x())>>4,(int)Math.floor(origin.z())>>4))continue;
            double reach=Math.max(portal.frame().halfWidth(),portal.frame().height())+2;
            BoundingBox box=BoundingBox.of(new Vector(origin.x(),origin.y()+portal.frame().height()/2,origin.z()),reach,reach,reach);
            for(Entity entity:world.getNearbyEntities(box)) {
                Kind kind=kind(entity);
                if(!allows(kind) || entity.isDead() || entity.isInsideVehicle() || !entity.getPassengers().isEmpty())continue;
                Location at=entity.getLocation();
                Point3 now=new Point3(at.getX(),at.getY(),at.getZ());
                Seen before=previous.get(entity.getUniqueId());
                next.put(entity.getUniqueId(),new Seen(world.getUID(),now));
                if(before==null || !before.world().equals(world.getUID()) || tick<cooldown.getOrDefault(entity.getUniqueId(),0L))continue;
                ViewFrame entry=PortalsPlugin.oriented(portal.frame(),before.at());
                if(!entry.crosses(before.at(),now,entity.getWidth()/2,entity.getHeight()))continue;
                if(moved>=options.perTick())continue;
                if(send(entity,portal,destination,entry,before.at(),tick)){moved++;next.remove(entity.getUniqueId());}
            }
        }
        previous=next;
        if(tick%200==0)cooldown.entrySet().removeIf(e->tick>=e.getValue());
    }

    private boolean send(Entity entity,Portal portal,Portal destination,ViewFrame entry,Point3 from,long tick) {
        World target=Bukkit.getWorld(destination.frame().world());
        if(target==null)return false;
        ViewFrame exitFrame=entry.quarterTurns()==portal.frame().quarterTurns()?destination.frame():PortalsPlugin.reverse(destination.frame());
        Vector v=entity.getVelocity();
        Exit exit=exit(entry,exitFrame,from,new Point3(v.getX(),v.getY(),v.getZ()),entity.getHeight());
        Point3 p=exit.position();
        if(!target.isChunkLoaded((int)Math.floor(p.x())>>4,(int)Math.floor(p.z())>>4) || !target.getWorldBorder().isInside(new Location(target,p.x(),p.y(),p.z())))return false;
        Location location=new Location(target,p.x(),p.y(),p.z());
        location.setDirection(new Vector(exit.facing().x(),exit.facing().y(),exit.facing().z()));
        if(!(entity instanceof Projectile)) {
            // Keep mobs and objects facing their own way, only rotated by the portal turn.
            Point3 look=entry.directionTo(exitFrame,toPoint(entity.getLocation().getDirection()));
            location.setDirection(new Vector(look.x(),look.y(),look.z()));
        }
        UUID id=entity.getUniqueId();
        if(!entity.teleport(location,org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN))return false;
        cooldown.put(id,tick+20);
        Vector velocity=new Vector(exit.velocity().x(),exit.velocity().y(),exit.velocity().z());
        // Cross-world teleports can replace the entity instance; look it up again.
        Entity arrived=Bukkit.getEntity(id);
        (arrived==null?entity:arrived).setVelocity(velocity);
        if(arrived!=null)arrived.setFallDistance(0);
        target.spawnParticle(Particle.REVERSE_PORTAL,location,6,.15,.15,.15,.02);
        return true;
    }
    private static Point3 toPoint(Vector v){return new Point3(v.getX(),v.getY(),v.getZ());}
    void close(){previous.clear();cooldown.clear();}
}
