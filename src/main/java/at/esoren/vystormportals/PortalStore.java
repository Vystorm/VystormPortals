package at.esoren.vystormportals;

import Vystorm.vystorm_core.visual.Point3;
import Vystorm.vystorm_core.visual.ViewFrame;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/** Schema 1 (optional per-portal "trusted" UUID list since 1.6.0), no legacy importer. Writes complete before callers replace their runtime snapshot. */
public final class PortalStore {
    private final Path file;
    public PortalStore(Path file) { this.file = file; }
    public Map<String, Portal> load() throws Exception {
        if (!Files.exists(file)) return Map.of();
        YamlConfiguration yaml = new YamlConfiguration(); yaml.load(file.toFile());
        if (!yaml.isInt("schema") || yaml.getInt("schema") != 1) throw new IOException("Unsupported portal schema");
        ConfigurationSection all = yaml.getConfigurationSection("portals");
        if (all == null) throw new IOException("Missing portals section");
        Map<String,Portal> result = new TreeMap<>();
        for (String id : all.getKeys(false)) {
            ConfigurationSection s = Objects.requireNonNull(all.getConfigurationSection(id));
            for (String field : List.of("world","owner","x","y","z","turns","half-width","height","public","warmup"))
                if (!s.contains(field)) throw new IOException("Missing " + field + " for " + id);
            for (String field : List.of("x","y","z","half-width","height"))
                if (!(s.get(field) instanceof Number)) throw new IOException("Invalid number " + field + " for " + id);
            for (String field : List.of("turns","warmup"))
                if (!s.isInt(field)) throw new IOException("Invalid integer " + field + " for " + id);
            if (s.contains("paused") && !s.isBoolean("paused")) throw new IOException("Invalid paused state for " + id);
            if (!s.isBoolean("public") || !s.isString("world") || !s.isString("owner")
                    || (s.contains("target") && !s.isString("target"))) throw new IOException("Invalid field type for " + id);
            ViewFrame frame = new ViewFrame(UUID.fromString(s.getString("world")),
                    new Point3(s.getDouble("x"),s.getDouble("y"),s.getDouble("z")),s.getInt("turns"),s.getDouble("half-width"),s.getDouble("height"));
            // Optional since 1.6.0; files without it load with nobody trusted.
            Set<UUID> trusted = new TreeSet<>();
            if (s.contains("trusted")) {
                if (!s.isList("trusted")) throw new IOException("Invalid trusted list for " + id);
                for (Object entry : s.getList("trusted")) {
                    if (!(entry instanceof String text)) throw new IOException("Invalid trusted player for " + id);
                    trusted.add(UUID.fromString(text));
                }
            }
            result.put(id,new Portal(id,UUID.fromString(s.getString("owner")),frame,s.getString("target"),s.getBoolean("public"),s.getInt("warmup"),trusted,s.getBoolean("paused",false)));
        }
        validate(result); return Collections.unmodifiableMap(result);
    }
    public void save(Map<String,Portal> portals) throws IOException {
        validate(portals);
        YamlConfiguration yaml = new YamlConfiguration(); yaml.set("schema",1); yaml.createSection("portals");
        for (Portal p : new TreeMap<>(portals).values()) {
            String base="portals."+p.id()+"."; ViewFrame f=p.frame();
            yaml.set(base+"owner",p.owner().toString()); yaml.set(base+"world",f.world().toString());
            yaml.set(base+"x",f.origin().x()); yaml.set(base+"y",f.origin().y()); yaml.set(base+"z",f.origin().z());
            yaml.set(base+"turns",f.quarterTurns()); yaml.set(base+"half-width",f.halfWidth()); yaml.set(base+"height",f.height());
            yaml.set(base+"target",p.target()); yaml.set(base+"public",p.publicAccess()); yaml.set(base+"warmup",p.warmupTicks());
            yaml.set(base+"paused",p.paused());
            if (!p.trusted().isEmpty()) yaml.set(base+"trusted",p.trusted().stream().map(UUID::toString).toList());
        }
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path temporary=Files.createTempFile(file.toAbsolutePath().getParent(),"portals-",".tmp");
        try {
            try (FileChannel channel=FileChannel.open(temporary,StandardOpenOption.WRITE,StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer bytes=StandardCharsets.UTF_8.encode(yaml.saveToString());
                while(bytes.hasRemaining()) channel.write(bytes); channel.force(true);
            }
            Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
    private static void validate(Map<String,Portal> portals) {
        if (portals.size()>256) throw new PortalInputException("portal-limit","Maximum 256 portals");
        portals.forEach((id,p)-> {
            if (!id.equals(p.id())) throw new IllegalArgumentException("Portal key mismatch");
            if(p.target()!=null && !portals.containsKey(p.target())) throw new IllegalArgumentException("Missing target for "+id);
        });
    }
}
