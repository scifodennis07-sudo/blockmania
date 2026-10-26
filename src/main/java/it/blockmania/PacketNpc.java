package it.blockmania;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.pose.EntityPose;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.player.Equipment;
import com.github.retrooper.packetevents.protocol.player.EquipmentSlot;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.protocol.player.TextureProperty;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityAnimation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEquipment;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityHeadLook;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRotation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoRemove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Un NPC "giocatore vero" fatto solo con i pacchetti (packetevents): skin completa, corpo da giocatore.
 * Esiste solo per i client a cui lo mostriamo, non e' un'entita' del server.
 */
final class PacketNpc {

    final String key;
    final int id;
    final UUID uuid = UUID.randomUUID();
    final UserProfile profile;
    final Location feet;
    final Set<UUID> viewers = new HashSet<>();
    float bodyYaw;
    float headYaw;
    float pitch;
    private boolean hand;

    PacketNpc(String key, String name, Location feet, String texValue, String texSignature) {
        this.key = key;
        this.id = io.github.retrooper.packetevents.util.SpigotReflectionUtil.generateEntityId();
        String shown = name.length() > 16 ? name.substring(0, 16) : name;
        this.profile = new UserProfile(uuid, shown);
        if (texValue != null && !texValue.isEmpty()) {
            profile.getTextureProperties().add(new TextureProperty("textures", texValue, texSignature));
        }
        this.feet = feet.clone();
        this.bodyYaw = feet.getYaw();
        this.headYaw = feet.getYaw();
    }

    private static void send(Player p, PacketWrapper<?> w) {
        PacketEvents.getAPI().getPlayerManager().sendPacket(p, w);
    }

    private void sendAll(PacketWrapper<?> w, List<Player> to) {
        for (Player p : to) {
            send(p, w);
        }
    }

    void show(Player p) {
        if (!viewers.add(p.getUniqueId())) {
            return;
        }
        WrapperPlayServerPlayerInfoUpdate.PlayerInfo info = new WrapperPlayServerPlayerInfoUpdate.PlayerInfo(
                profile, false, 0, GameMode.CREATIVE, null, null);
        send(p, new WrapperPlayServerPlayerInfoUpdate(WrapperPlayServerPlayerInfoUpdate.Action.ADD_PLAYER, info));
        send(p, new WrapperPlayServerSpawnEntity(id, Optional.of(uuid), EntityTypes.PLAYER,
                new Vector3d(feet.getX(), feet.getY(), feet.getZ()), pitch, bodyYaw, headYaw, 0, Optional.empty()));
        List<EntityData<?>> meta = new ArrayList<>();
        meta.add(new EntityData<>(17, EntityDataTypes.BYTE, (byte) 0x7F)); // tutti i livelli della skin
        send(p, new WrapperPlayServerEntityMetadata(id, meta));
        send(p, new WrapperPlayServerEntityRotation(id, bodyYaw, pitch, true));
        send(p, new WrapperPlayServerEntityHeadLook(id, headYaw));
    }

    void hide(Player p) {
        if (!viewers.remove(p.getUniqueId())) {
            return;
        }
        send(p, new WrapperPlayServerDestroyEntities(id));
        send(p, new WrapperPlayServerPlayerInfoRemove(uuid));
    }

    private List<Player> online() {
        List<Player> out = new ArrayList<>();
        for (UUID u : viewers) {
            Player p = org.bukkit.Bukkit.getPlayer(u);
            if (p != null && p.isOnline()) {
                out.add(p);
            }
        }
        return out;
    }

    /** Il corpo segue la testa solo se questa si gira troppo, come in gioco. */
    void look(float wantedYaw, float wantedPitch) {
        headYaw = wantedYaw;
        pitch = Math.max(-60f, Math.min(60f, wantedPitch));
        float diff = wantedYaw - bodyYaw;
        while (diff > 180f) {
            diff -= 360f;
        }
        while (diff < -180f) {
            diff += 360f;
        }
        if (Math.abs(diff) > 50f) {
            bodyYaw += diff - Math.signum(diff) * 50f;
        }
        List<Player> to = online();
        sendAll(new WrapperPlayServerEntityRotation(id, bodyYaw, pitch, true), to);
        sendAll(new WrapperPlayServerEntityHeadLook(id, headYaw), to);
    }

    /** Sposta l'NPC rispetto al suo punto (passi, saltello, spinte). */
    void place(double dx, double dy, double dz) {
        sendAll(new WrapperPlayServerEntityTeleport(id,
                new Vector3d(feet.getX() + dx, feet.getY() + dy, feet.getZ() + dz), bodyYaw, pitch, dy <= 0.001), online());
    }

    void lift(double dy) {
        place(0, dy, 0);
    }

    void swing(boolean offHand) {
        sendAll(new WrapperPlayServerEntityAnimation(id, offHand
                ? WrapperPlayServerEntityAnimation.EntityAnimationType.SWING_OFF_HAND
                : WrapperPlayServerEntityAnimation.EntityAnimationType.SWING_MAIN_ARM), online());
    }

    void crouch(boolean on) {
        List<EntityData<?>> meta = new ArrayList<>();
        meta.add(new EntityData<>(0, EntityDataTypes.BYTE, (byte) (on ? 0x02 : 0x00)));
        meta.add(new EntityData<>(6, EntityDataTypes.ENTITY_POSE, on ? EntityPose.CROUCHING : EntityPose.STANDING));
        sendAll(new WrapperPlayServerEntityMetadata(id, meta), online());
    }

    /** Tiene in mano un oggetto e lo "usa": il braccio si piega (mangiare = mano alla bocca, scudo = braccio alzato). */
    void useItem(Material item, boolean on) {
        List<Player> to = online();
        List<Equipment> eq = new ArrayList<>();
        eq.add(new Equipment(EquipmentSlot.MAIN_HAND, SpigotConversionUtil.fromBukkitItemStack(
                new org.bukkit.inventory.ItemStack(on ? item : Material.AIR))));
        sendAll(new WrapperPlayServerEntityEquipment(id, eq), to);
        List<EntityData<?>> meta = new ArrayList<>();
        meta.add(new EntityData<>(8, EntityDataTypes.BYTE, (byte) (on ? 1 : 0)));
        sendAll(new WrapperPlayServerEntityMetadata(id, meta), to);
        hand = on;
    }

    boolean handBusy() {
        return hand;
    }

    void destroy() {
        for (Player p : online()) {
            send(p, new WrapperPlayServerDestroyEntities(id));
            send(p, new WrapperPlayServerPlayerInfoRemove(uuid));
        }
        viewers.clear();
    }
}
