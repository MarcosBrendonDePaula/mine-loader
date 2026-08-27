package dev.lualoader.neoforge;

import dev.lualoader.platform.BridgeException;
import dev.lualoader.neoforge.network.NeoForgeScreenNetwork;
import dev.lualoader.neoforge.network.NeoForgeScreenPayloads;
import dev.lualoader.platform.PlayerHandle;
import dev.lualoader.ui.ScreenProtocol;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * O mesmo contrato de jogador do adaptador Fabric, respondido com as APIs do NeoForge.
 *
 * <p>Cobertura parcial e deliberada: mensagens, inventário, posição e vida primeiro, porque são o
 * que a maioria dos mods usa.
 *
 * <p>Os menus funcionam porque são a tela de baú do jogo — nada precisa existir do lado do cliente.
 * As telas desenhadas são outra história: dependem de um protocolo de rede e de um renderizador que
 * este adaptador ainda não tem. Elas respondem {@code false} em vez de lançar, porque o contrato
 * prevê essa recusa justamente para o mod poder escolher um caminho alternativo — e o menu é esse
 * caminho.
 */
public class NeoForgePlayerHandle implements PlayerHandle {
    private final ServerPlayer player;

    public NeoForgePlayerHandle(ServerPlayer player) {
        this.player = player;
    }

    private static BridgeException pending(String operation) {
        return new BridgeException(operation + " ainda nao existe no adaptador NeoForge");
    }

    private static Item requireItem(String itemId) {
        ResourceLocation id = ResourceLocation.tryParse(itemId);
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
            throw new BridgeException("item desconhecido: " + itemId);
        }
        return BuiltInRegistries.ITEM.get(id);
    }

    // ------------------------------------------------------------------ identidade e mensagens

    @Override
    public int permissionLevel() {
        // O jogo guarda o nivel na lista de operadores; quatro e o maximo.
        for (int level = 4; level >= 1; level--) {
            if (player.hasPermissions(level)) return level;
        }
        return 0;
    }

    @Override
    public String name() {
        return player.getName().getString();
    }

    @Override
    public String uuid() {
        return player.getUUID().toString();
    }

    @Override
    public void sendMessage(String message) {
        player.sendSystemMessage(Component.literal(message));
    }

    @Override
    public void sendActionBar(String message) {
        player.displayClientMessage(Component.literal(message), true);
    }

    // ------------------------------------------------------------------ inventario

    @Override
    public String heldItem() {
        ItemStack stack = player.getMainHandItem();
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    @Override
    public int countItem(String itemId) {
        Item item = requireItem(itemId);
        int total = 0;

        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(item)) total += stack.getCount();
        }
        return total;
    }

    @Override
    public int giveItem(String itemId, int count) {
        ItemStack stack = new ItemStack(requireItem(itemId), count);
        boolean coube = player.getInventory().add(stack);

        // O que nao coube vai para o chao, e o retorno diz quanto foi: um script que checa o
        // retorno precisa saber que o item existe em algum lugar, e nao que sumiu.
        if (!coube && !stack.isEmpty()) {
            player.drop(stack, false);
            return stack.getCount();
        }
        return 0;
    }

    @Override
    public int giveItem(String itemId, int count, dev.lualoader.platform.ItemSpec spec) {
        if (spec == null || spec.isEmpty()) return giveItem(itemId, count);

        ItemStack stack = new ItemStack(requireItem(itemId), count);
        applySpec(stack, spec, player.level());

        boolean coube = player.getInventory().add(stack);
        if (!coube && !stack.isEmpty()) {
            player.drop(stack, false);
            return stack.getCount();
        }
        return 0;
    }

    /**
     * Aplica o que o mod declarou sobre o item.
     *
     * <p>Traduz para componentes, que é como esta versão do jogo guarda essas coisas. Um mod que
     * escrevesse NBT cru teria escrito a forma anterior e pararia de funcionar na 1.20.5 sem ter
     * mudado uma linha — é o motivo de o vocabulário ser declarado.
     */
    static void applySpec(ItemStack stack, dev.lualoader.platform.ItemSpec spec,
                          net.minecraft.world.level.Level level) {
        if (spec.name != null) {
            stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                    Component.literal(spec.name));
        }
        if (spec.lore != null && !spec.lore.isEmpty()) {
            List<Component> lines = new java.util.ArrayList<>();
            for (String line : spec.lore) lines.add(Component.literal(line));
            stack.set(net.minecraft.core.component.DataComponents.LORE,
                    new net.minecraft.world.item.component.ItemLore(lines));
        }
        if (spec.damage != null) {
            // Recortado ao maximo do item: um dano acima dele quebraria a peca na hora de aparecer,
            // e o mod veria o item sumir sem explicacao.
            stack.setDamageValue(Math.min(spec.damage, Math.max(0, stack.getMaxDamage())));
        }
        if (Boolean.TRUE.equals(spec.unbreakable)) {
            stack.set(net.minecraft.core.component.DataComponents.UNBREAKABLE,
                    new net.minecraft.world.item.component.Unbreakable(true));
        }
        if (spec.customModelData != null) {
            stack.set(net.minecraft.core.component.DataComponents.CUSTOM_MODEL_DATA,
                    new net.minecraft.world.item.component.CustomModelData(spec.customModelData));
        }

        if (spec.enchantments == null || spec.enchantments.isEmpty()) return;

        // Encantamento deixou de ser um registro fixo e passou a vir do datapack, entao so existe
        // com um mundo carregado -- e por isso a consulta sai do registro do nivel, e nao de
        // BuiltInRegistries como os itens e blocos.
        var registry = level.registryAccess()
                .registry(net.minecraft.core.registries.Registries.ENCHANTMENT)
                .orElse(null);
        if (registry == null) return;

        var enchantments = new net.minecraft.world.item.enchantment.ItemEnchantments.Mutable(
                net.minecraft.world.item.enchantment.ItemEnchantments.EMPTY);
        boolean applied = false;

        for (var entry : spec.enchantments.entrySet()) {
            ResourceLocation id = ResourceLocation.tryParse(entry.getKey());
            if (id == null) continue;

            var found = registry.getHolder(
                    net.minecraft.resources.ResourceKey.create(
                            net.minecraft.core.registries.Registries.ENCHANTMENT, id))
                    .orElse(null);
            if (found == null) continue;

            enchantments.set(found, entry.getValue());
            applied = true;
        }
        if (applied) {
            stack.set(net.minecraft.core.component.DataComponents.ENCHANTMENTS,
                    enchantments.toImmutable());
        }
    }

    @Override
    public int takeItem(String itemId, int count) {
        Item item = requireItem(itemId);
        int removed = 0;

        for (int slot = 0; slot < player.getInventory().getContainerSize() && removed < count; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.is(item)) continue;

            int taken = Math.min(stack.getCount(), count - removed);
            stack.shrink(taken);
            removed += taken;
        }
        return removed;
    }

    // ------------------------------------------------------------------ corpo

    @Override
    public int[] position() {
        return new int[]{player.getBlockX(), player.getBlockY(), player.getBlockZ()};
    }

    @Override
    public float[] health() {
        return new float[]{player.getHealth(), player.getMaxHealth()};
    }

    @Override
    public int[] lookingAt(double maxDistance) {
        // NONE para liquido: quem mira num bloco dentro d'agua quer o bloco, e nao a superficie.
        var resultado = player.pick(maxDistance, 0f, false);
        if (!(resultado instanceof net.minecraft.world.phys.BlockHitResult hit)) return null;
        if (resultado.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) return null;

        var pos = hit.getBlockPos();
        return new int[]{pos.getX(), pos.getY(), pos.getZ(), hit.getDirection().get3DDataValue()};
    }

    @Override
    public void teleport(double x, double y, double z) {
        player.teleportTo(x, y, z);
    }

    // ------------------------------------------------------------------ janelas

    @Override
    public boolean openBlockInventory(int x, int y, int z) {
        var pos = new net.minecraft.core.BlockPos(x, y, z);
        var level = player.level();

        // A fabrica vem do bloco, e nao de uma tabela nossa: e o mesmo caminho do clique, entao um
        // bloco que abre algo pelo clique abre a mesma coisa por aqui.
        var provider = level.getBlockState(pos).getMenuProvider(level, pos);
        if (provider == null) return false;

        // Com janela declarada a posicao vai junto, como no clique: e o unico dado que o cliente
        // precisa para achar o desenho no manifesto que ele ja tem.
        player.openMenu(provider, buffer -> buffer.writeBlockPos(pos));
        return true;
    }

    @Override
    public void openMenu(String menuId, String title, int rows, List<String> items) {
        int lines = Math.max(1, Math.min(6, rows));
        var contents = NeoForgeLuaMenu.build(items, lines);

        // O mod dono do menu sai do proprio identificador, como no adaptador Fabric: e o que o
        // runtime usa para nao entregar o clique ao script errado.
        String modId = menuId.contains(":") ? menuId.substring(0, menuId.indexOf(':')) : menuId;

        player.openMenu(new SimpleMenuProvider(
                (containerId, playerInventory, ignored) -> new NeoForgeLuaMenu(
                        containerId, playerInventory, contents, lines, menuId, modId),
                Component.literal(title)));
    }

    @Override
    public boolean updateMenu(List<String> items) {
        if (!(player.containerMenu instanceof NeoForgeLuaMenu menu)) return false;
        menu.replaceContents(items);
        return true;
    }

    @Override
    public String openMenuId() {
        return player.containerMenu instanceof NeoForgeLuaMenu menu ? menu.menuId() : null;
    }

    @Override
    public void closeMenu() {
        player.closeContainer();
    }

    // ------------------------------------------------------------------ telas desenhadas

    /** Tela do loader aberta por jogador. O servidor precisa saber o que cada um esta vendo. */
    private static final Map<UUID, String> OPEN_SCREENS = new ConcurrentHashMap<>();

    /** Tamanho de tela informado por cada cliente, em unidades de interface. */
    private static final Map<UUID, int[]> SCREEN_SIZES = new ConcurrentHashMap<>();

    @Override
    public boolean supportsScreens() {
        return NeoForgeScreenNetwork.supports(player);
    }

    @Override
    public boolean openScreen(String screenId, String descriptionJson) {
        if (!supportsScreens()) {
            NeoForgeLuaLoader.LOGGER.warn(
                    "Cliente de {} nao registrou o canal de telas; {} nao foi aberta",
                    player.getName().getString(), screenId);
            return false;
        }

        NeoForgeScreenNetwork.send(player, new NeoForgeScreenPayloads.OpenScreen(
                ScreenProtocol.VERSION, screenId, descriptionJson));
        OPEN_SCREENS.put(player.getUUID(), screenId);
        return true;
    }

    @Override
    public boolean updateScreen(String descriptionJson) {
        if (!supportsScreens() || OPEN_SCREENS.get(player.getUUID()) == null) return false;

        NeoForgeScreenNetwork.send(player,
                new NeoForgeScreenPayloads.UpdateScreen(descriptionJson));
        return true;
    }

    @Override
    public void closeScreen() {
        OPEN_SCREENS.remove(player.getUUID());
        if (!supportsScreens()) return;

        NeoForgeScreenNetwork.send(player, new NeoForgeScreenPayloads.CloseScreen(""));
    }

    @Override
    public String openScreenId() {
        return OPEN_SCREENS.get(player.getUUID());
    }

    /** Chamado quando o cliente avisa que fechou a tela. */
    public static void forgetScreen(UUID playerId) {
        OPEN_SCREENS.remove(playerId);
    }

    @Override
    public boolean setHud(String descriptionJson) {
        if (!supportsScreens()) return false;

        NeoForgeScreenNetwork.send(player, new NeoForgeScreenPayloads.SetHud(descriptionJson));
        return true;
    }

    @Override
    public boolean setOverlay(String overlayId, String descriptionJson) {
        if (!supportsScreens()) {
            NeoForgeLuaLoader.LOGGER.warn(
                    "Cliente de {} nao registrou o canal de telas; a sobreposicao {} nao foi enviada",
                    player.getName().getString(), overlayId);
            return false;
        }

        NeoForgeScreenNetwork.send(player, new NeoForgeScreenPayloads.SetOverlay(
                ScreenProtocol.VERSION, overlayId, descriptionJson));
        return true;
    }

    @Override
    public boolean clearOverlay(String overlayId) {
        if (!supportsScreens()) return false;

        NeoForgeScreenNetwork.send(player, new NeoForgeScreenPayloads.ClearOverlay(overlayId));
        return true;
    }

    /** Chamado quando o cliente informa o tamanho, ao entrar e a cada mudanca de escala. */
    public static void rememberScreenSize(UUID playerId, int width, int height) {
        SCREEN_SIZES.put(playerId, new int[]{width, height});
    }

    public static void forgetScreenSize(UUID playerId) {
        SCREEN_SIZES.remove(playerId);
    }

    @Override
    public int[] screenSize() {
        int[] size = SCREEN_SIZES.get(player.getUUID());
        // Uma copia, para o script nao poder escrever no que o servidor guarda.
        return size == null ? null : size.clone();
    }

    // ------------------------------------------------------------------ corpo, escrita

    @Override
    public void setHealth(float health) {
        // Recortado ao maximo: escrever acima dele faria o jogo devolver o valor ao teto, e o mod
        // veria a declaracao sumir sem erro.
        player.setHealth(Math.min(health, player.getMaxHealth()));
    }

    @Override
    public float[] food() {
        var hunger = player.getFoodData();
        return new float[]{hunger.getFoodLevel(), hunger.getSaturationLevel()};
    }

    @Override
    public void setFood(int level, float saturation) {
        var hunger = player.getFoodData();
        hunger.setFoodLevel(Math.max(0, Math.min(20, level)));
        hunger.setSaturation(saturation);
    }

    @Override
    public float[] experience() {
        return new float[]{player.experienceLevel, player.experienceProgress};
    }

    @Override
    public void giveExperienceLevels(int levels) {
        player.giveExperienceLevels(levels);
    }

    @Override
    public String gameMode() {
        return player.gameMode.getGameModeForPlayer().getName();
    }

    @Override
    public void setGameMode(String mode) {
        var found = net.minecraft.world.level.GameType.byName(mode, null);
        if (found == null) throw new BridgeException("modo de jogo desconhecido: " + mode);
        player.setGameMode(found);
    }

    @Override
    public String dimension() {
        return player.level().dimension().location().toString();
    }

    @Override
    public void applyEffect(String effectId, int duration, int amplifier) {
        ResourceLocation id = ResourceLocation.tryParse(effectId);
        if (id == null) throw new BridgeException("identificador de efeito invalido: " + effectId);

        var effect = BuiltInRegistries.MOB_EFFECT.getHolder(
                net.minecraft.resources.ResourceKey.create(
                        net.minecraft.core.registries.Registries.MOB_EFFECT, id)).orElse(null);
        if (effect == null) throw new BridgeException("efeito desconhecido: " + effectId);

        player.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                effect, duration, amplifier));
    }

    @Override
    public void clearEffects() {
        player.removeAllEffects();
    }

    @Override
    public java.util.List<ActiveEffect> activeEffects() {
        java.util.List<ActiveEffect> result = new java.util.ArrayList<>();
        for (var effect : player.getActiveEffects()) {
            ResourceLocation id = BuiltInRegistries.MOB_EFFECT.getKey(effect.getEffect().value());
            if (id == null) continue;
            result.add(new ActiveEffect(id.toString(), effect.getDuration(), effect.getAmplifier(),
                    effect.isAmbient(), effect.isVisible()));
        }
        return java.util.List.copyOf(result);
    }

    @Override
    public Movement movement() {
        var velocity = player.getDeltaMovement();
        return new Movement(velocity.x, velocity.y, velocity.z,
                player.onGround(), player.isCrouching(), player.isSprinting(),
                player.isSwimming(), player.getAbilities().flying, player.isFallFlying());
    }

    // ------------------------------------------------------------------ feedback

    @Override
    public void showTitle(String title, String subtitle, int fadeIn, int stay, int fadeOut) {
        // Os tempos vao antes do texto: enviados depois, so valeriam no proximo titulo, e o
        // primeiro apareceria com a duracao anterior.
        if (fadeIn >= 0 || stay >= 0 || fadeOut >= 0) {
            player.connection.send(new net.minecraft.network.protocol.game
                    .ClientboundSetTitlesAnimationPacket(
                    fadeIn < 0 ? 10 : fadeIn, stay < 0 ? 70 : stay, fadeOut < 0 ? 20 : fadeOut));
        }
        if (subtitle != null && !subtitle.isBlank()) {
            player.connection.send(new net.minecraft.network.protocol.game
                    .ClientboundSetSubtitleTextPacket(Component.literal(subtitle)));
        }
        player.connection.send(new net.minecraft.network.protocol.game
                .ClientboundSetTitleTextPacket(Component.literal(title == null ? "" : title)));
    }

    @Override
    public void playSoundTo(String soundId, float volume, float pitch) {
        ResourceLocation id = ResourceLocation.tryParse(soundId);
        if (id == null) throw new BridgeException("identificador de som invalido: " + soundId);

        var sound = BuiltInRegistries.SOUND_EVENT.getHolder(
                net.minecraft.resources.ResourceKey.create(
                        net.minecraft.core.registries.Registries.SOUND_EVENT, id)).orElse(null);
        if (sound == null) throw new BridgeException("som desconhecido: " + soundId);

        // Na posicao do jogador, e nao no mundo: o som acompanha quem o recebeu, que e o que
        // distingue um retorno de interface de um ruido do ambiente.
        player.connection.send(new net.minecraft.network.protocol.game
                .ClientboundSoundPacket(sound, net.minecraft.sounds.SoundSource.PLAYERS,
                player.getX(), player.getY(), player.getZ(), volume, pitch,
                player.level().getRandom().nextLong()));
    }

    // ------------------------------------------------------------------ inventário

    @Override
    public List<String> inventory() {
        List<String> contents = new java.util.ArrayList<>();
        var inventory = player.getInventory();

        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) continue;

            ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            contents.add(slot + ";" + (id == null ? "minecraft:air" : id) + ";" + stack.getCount());
        }
        return contents;
    }

    @Override
    public PlayerHandle.ItemStackView inventorySlot(int slot) {
        var inventory = player.getInventory();
        validateSlot(slot, inventory.getContainerSize());
        return stackView(inventory.getItem(slot));
    }

    @Override
    public void setInventorySlot(int slot, String itemId, int count, dev.lualoader.platform.ItemSpec spec) {
        var inventory = player.getInventory();
        validateSlot(slot, inventory.getContainerSize());
        if (count == 0) {
            inventory.setItem(slot, ItemStack.EMPTY);
            inventory.setChanged();
            return;
        }

        Item item = requireItem(itemId);
        if (count > new ItemStack(item).getMaxStackSize()) {
            throw new BridgeException("quantidade excede o stack maximo do item: " + itemId);
        }
        ItemStack stack = new ItemStack(item, count);
        if (spec != null && !spec.isEmpty()) applySpec(stack, spec, player.level());
        inventory.setItem(slot, stack);
        inventory.setChanged();
    }

    @Override
    public PlayerHandle.Equipment equipment() {
        return new PlayerHandle.Equipment(
                stackView(player.getMainHandItem()),
                stackView(player.getOffhandItem()),
                stackView(player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD)),
                stackView(player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST)),
                stackView(player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.LEGS)),
                stackView(player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.FEET)));
    }

    @Override
    public void clearInventory() {
        player.getInventory().clearContent();
    }

    private static void validateSlot(int slot, int size) {
        if (slot < 0 || slot >= size) {
            throw new BridgeException("slot de inventario fora do intervalo: " + slot);
        }
    }

    private static PlayerHandle.ItemStackView stackView(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return new PlayerHandle.ItemStackView("minecraft:air", 0);
        }
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return new PlayerHandle.ItemStackView(id == null ? "minecraft:air" : id.toString(), stack.getCount());
    }
}
