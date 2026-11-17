package at.esoren.vystormportals;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import Vystorm.vystorm_core.visual.*;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.junit.jupiter.api.Test;

class EntityTravelTest {
    private static final UUID W=UUID.randomUUID();

    @Test void straightPortalKeepsMomentumAndMirrorsSideways() {
        ViewFrame in=new ViewFrame(W,new Point3(.5,64,.5),0,1.5,3), out=new ViewFrame(W,new Point3(100.5,70,.5),0,1.5,3);
        ViewFrame entry=PortalsPlugin.oriented(in,new Point3(1,65,1.5));
        var exit=EntityTravel.exit(entry,entry.quarterTurns()==in.quarterTurns()?out:PortalsPlugin.reverse(out),
                new Point3(1,65,.9),new Point3(0,.1,-.8),.25);
        // Same height above the frame bottom, momentum kept in size.
        assertEquals(71,exit.position().y(),1e-9);
        double speed=Math.sqrt(.1*.1+.8*.8);
        Point3 v=exit.velocity();
        assertEquals(speed,Math.sqrt(v.x()*v.x()+v.y()*v.y()+v.z()*v.z()),1e-9);
        assertEquals(.1,v.y(),1e-9);
    }

    @Test void turnedPortalRotatesVelocity() {
        ViewFrame in=new ViewFrame(W,new Point3(.5,64,.5),0,1.5,3), out=new ViewFrame(W,new Point3(.5,64,50.5),1,1.5,3);
        var exit=EntityTravel.exit(in,out,new Point3(.5,65,.2),new Point3(0,0,1),.25);
        Point3 v=exit.velocity();
        assertEquals(1,Math.abs(v.x())+Math.abs(v.z()),1e-9);
        assertEquals(0,Math.min(Math.abs(v.x()),Math.abs(v.z())),1e-9);
    }

    @Test void tallEntitiesStayInsideTheExitFrame() {
        ViewFrame in=new ViewFrame(W,new Point3(.5,64,.5),0,1,2), out=new ViewFrame(W,new Point3(.5,10,.5),0,1,2);
        var exit=EntityTravel.exit(in,out,new Point3(.5,65.9,.2),new Point3(0,0,0),1.9);
        assertEquals(10.1,exit.position().y(),1e-9);
    }

    @Test void classifiesEntities() {
        assertEquals(EntityTravel.Kind.OBJECT,EntityTravel.kind(mock(Item.class)));
        assertEquals(EntityTravel.Kind.OBJECT,EntityTravel.kind(mock(TNTPrimed.class)));
        assertEquals(EntityTravel.Kind.PROJECTILE,EntityTravel.kind(mock(Arrow.class)));
        assertEquals(EntityTravel.Kind.PROJECTILE,EntityTravel.kind(mock(EnderPearl.class)));
        assertEquals(EntityTravel.Kind.MOB,EntityTravel.kind(mock(Zombie.class)));
        assertEquals(EntityTravel.Kind.VEHICLE,EntityTravel.kind(mock(Minecart.class)));
        assertNull(EntityTravel.kind(mock(Player.class)));
        assertNull(EntityTravel.kind(mock(FishHook.class)));
        assertNull(EntityTravel.kind(mock(Wither.class)));
        assertNull(EntityTravel.kind(mock(EnderDragon.class)));
        assertNull(EntityTravel.kind(mock(ArmorStand.class)));
    }

    @Test void optionsSwitchGroupsIndividually() {
        var config=new YamlConfiguration();
        config.set("entity-travel.mobs",false);
        var travel=new EntityTravel(null,EntityTravel.Options.read(config));
        assertTrue(travel.allows(EntityTravel.Kind.OBJECT));
        assertFalse(travel.allows(EntityTravel.Kind.MOB));
        assertFalse(travel.allows(null));
    }

    @Test void numbersGiveReadableErrors() {
        assertEquals(4,PortalsPlugin.number(" 4 ","width"));
        var error=assertThrows(IllegalArgumentException.class,()->PortalsPlugin.number("vier","width"));
        assertEquals("width must be a whole number, got \"vier\"",error.getMessage());
    }
}
