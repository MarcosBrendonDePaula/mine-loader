package dev.lualoader.minecraft;

import dev.lualoader.platform.BridgeException;
import dev.lualoader.platform.PlayerHandle;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/** Embrulha a entidade de jogador do Fabric na referência neutra usada pelo núcleo. */
public record FabricPlayerHandle(ServerPlayerEntity player) implements PlayerHandle {
    @Override
    public int permissionLevel() {
        // O jogo guarda o nivel na lista de operadores; quatro e o maximo.
        for (int level = 4; level >= 1; level--) {
            if (player.hasPermissionLevel(level)) return level;
        }
        return 0;
    }

    @Override
    public String name() {
        return player.getName().getString();
    }

    @Override
    public String uuid() {
        return player.getUuidAsString();
    }

    @Override
    public void sendMessage(String message) {
        player.sendMessage(Text.literal(message), false);
    }

    @Override
    public void sendActionBar(String message) {
        player.sendMessage(Text.literal(message), true);
    }

    @Override
    public String heldItem() {
        ItemStack stack = player.getMainHandStack();
        if (stack.isEmpty()) return "minecraft:air";
        Identifier id = Registries.ITEM.getId(stack.getItem());
        return id == null ? "minecraft:air" : id.toString();
    }

    @Override
    public int countItem(String itemId) {
        Item item = resolveItem(itemId);
        int total = 0;
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.getItem() == item) total += stack.getCount();
        }
        return total;
    }

    @Override
    public int giveItem(String itemId, int count) {
        Item item = resolveItem(itemId);
        int remaining = count;
        int dropped = 0;

        while (remaining > 0) {
            int batch = Math.min(remaining, item.getMaxCount());
            ItemStack stack = new ItemStack(item, batch);

            player.getInventory().insertStack(stack);
            if (!stack.isEmpty()) {
                // O que nao coube cai no mundo, para o item nao sumir em silencio, e e reportado
                // ao script, que pode querer avisar o jogador ou desfazer a operacao.
                dropped += stack.getCount();
                player.dropItem(stack, false);
            }
            remaining -= batch;
        }
        return dropped;
    }

    @Override
    public int giveItem(String itemId, int count, dev.lualoader.platform.ItemSpec spec) {
        if (spec == null || spec.isEmpty()) return giveItem(itemId, count);

        Item item = resolveItem(itemId);
        int remaining = count;
        int dropped = 0;

        while (remaining > 0) {
            int batch = Math.min(remaining, item.getMaxCount());
            ItemStack stack = new ItemStack(item, batch);
            applySpec(stack, spec, player.getWorld());

            player.getInventory().insertStack(stack);
            if (!stack.isEmpty()) {
                dropped += stack.getCount();
                player.dropItem(stack, false);
            }
            remaining -= batch;
        }
        return dropped;
    }

    /**
     * Aplica o que o mod declarou sobre o item.
     *
     * <p>Traduz para componentes, que é como esta versão do jogo guarda essas coisas. Um mod que
     * escrevesse NBT cru teria escrito a forma anterior e pararia de funcionar na 1.20.5 sem ter
     * mudado uma linha — é o motivo de o vocabulário ser declarado.
     */
    static void applySpec(ItemStack stack, dev.lualoader.platform.ItemSpec spec,
                                  net.minecraft.world.World world) {
        if (spec.name != null) {
            stack.set(net.minecraft.component.DataComponentTypes.CUSTOM_NAME,
                    Text.literal(spec.name));
        }
        if (spec.lore != null && !spec.lore.isEmpty()) {
            java.util.List<Text> lines = new java.util.ArrayList<>();
            for (String line : spec.lore) lines.add(Text.literal(line));
            stack.set(net.minecraft.component.DataComponentTypes.LORE,
                    new net.minecraft.component.type.LoreComponent(lines));
        }
        if (spec.damage != null) {
            // Recortado ao maximo do item: um dano acima dele quebraria a peca na hora de aparecer,
            // e o mod veria o item sumir sem explicacao.
            stack.setDamage(Math.min(spec.damage, Math.max(0, stack.getMaxDamage())));
        }
        if (Boolean.TRUE.equals(spec.unbreakable)) {
            stack.set(net.minecraft.component.DataComponentTypes.UNBREAKABLE,
                    new net.minecraft.component.type.UnbreakableComponent(true));
        }
        if (spec.customModelData != null) {
            stack.set(net.minecraft.component.DataComponentTypes.CUSTOM_MODEL_DATA,
                    new net.minecraft.component.type.CustomModelDataComponent(
                            spec.customModelData));
        }

        if (spec.enchantments == null || spec.enchantments.isEmpty()) return;

        // Encantamento deixou de ser um registro fixo e passou a vir do datapack, entao so existe
        // com um mundo carregado -- e por isso a consulta sai do registro do mundo, e nao de
        // Registries como os itens e blocos.
        var registry = world.getRegistryManager()
                .getOptional(net.minecraft.registry.RegistryKeys.ENCHANTMENT)
                .orElse(null);
        if (registry == null) return;

        var builder = new net.minecraft.component.type.ItemEnchantmentsComponent.Builder(
                net.minecraft.component.type.ItemEnchantmentsComponent.DEFAULT);
        boolean applied = false;

        for (var entry : spec.enchantments.entrySet()) {
            Identifier id = Identifier.tryParse(entry.getKey());
            if (id == null) continue;

            var found = registry.getEntry(id).orElse(null);
            if (found == null) continue;

            builder.add(found, entry.getValue());
            applied = true;
        }
        if (applied) {
            stack.set(net.minecraft.component.DataComponentTypes.ENCHANTMENTS, builder.build());
        }
    }

    @Override
    public int takeItem(String itemId, int count) {
        Item item = resolveItem(itemId);
        int remaining = count;
        var inventory = player.getInventory();

        for (int slot = 0; slot < inventory.size() && remaining > 0; slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.getItem() != item) continue;

            int removed = Math.min(remaining, stack.getCount());
            stack.decrement(removed);
            remaining -= removed;
            if (stack.isEmpty()) inventory.setStack(slot, ItemStack.EMPTY);
        }
        return count - remaining;
    }

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
        var resultado = player.raycast(maxDistance, 0f, false);
        if (!(resultado instanceof net.minecraft.util.hit.BlockHitResult hit)) return null;
        if (resultado.getType() != net.minecraft.util.hit.HitResult.Type.BLOCK) return null;

        var pos = hit.getBlockPos();
        return new int[]{pos.getX(), pos.getY(), pos.getZ(), hit.getSide().getId()};
    }

    @Override
    public void teleport(double x, double y, double z) {
        player.teleport(player.getServerWorld(), x, y, z,
                java.util.Set.of(), player.getYaw(), player.getPitch());
    }

    @Override
    public boolean openBlockInventory(int x, int y, int z) {
        var pos = new net.minecraft.util.math.BlockPos(x, y, z);
        var world = player.getWorld();

        // A fabrica vem do bloco, e nao de uma tabela nossa: e o mesmo caminho do clique, entao um
        // bloco que abre algo pelo clique abre a mesma coisa por aqui.
        var factory = world.getBlockState(pos).createScreenHandlerFactory(world, pos);
        if (factory == null) return false;

        player.openHandledScreen(factory);
        return true;
    }

    @Override
    public void openMenu(String menuId, String title, int rows, java.util.List<String> items) {
        int lines = Math.max(1, Math.min(6, rows));
        var content = LuaMenu.build(items, lines);
        String modId = menuId.contains(":") ? menuId.substring(0, menuId.indexOf(':')) : menuId;

        player.openHandledScreen(new net.minecraft.screen.SimpleNamedScreenHandlerFactory(
                (syncId, playerInventory, ignored) ->
                        new LuaMenu(syncId, playerInventory, content, lines, menuId, modId),
                Text.literal(title)));
    }

    @Override
    public boolean updateMenu(java.util.List<String> items) {
        if (!(player.currentScreenHandler instanceof LuaMenu menu)) return false;
        menu.replaceContents(items);
        return true;
    }

    @Override
    public String openMenuId() {
        return player.currentScreenHandler instanceof LuaMenu menu ? menu.menuId() : null;
    }

    @Override
    public void closeMenu() {
        player.closeHandledScreen();
    }

    // --- Telas desenhadas ------------------------------------------------------------------

    /** Tela do loader aberta por jogador. O servidor precisa saber o que cada um esta vendo. */
    private static final java.util.Map<java.util.UUID, String> TELAS_ABERTAS =
            new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public boolean supportsScreens() {
        return dev.lualoader.network.ScreenNetwork.supports(player);
    }

    @Override
    public boolean openScreen(String screenId, String descriptionJson) {
        if (!supportsScreens()) {
            dev.lualoader.LuaLoaderMod.LOGGER.warn(
                    "Cliente de {} nao registrou o canal de telas; {} nao foi aberta",
                    player.getName().getString(), screenId);
            return false;
        }
        dev.lualoader.LuaLoaderMod.LOGGER.info("Enviando tela {} para {} ({} caracteres)",
                screenId, player.getName().getString(), descriptionJson.length());

        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player,
                new dev.lualoader.network.ScreenPayloads.OpenScreen(
                        dev.lualoader.ui.ScreenProtocol.VERSION, screenId, descriptionJson));
        TELAS_ABERTAS.put(player.getUuid(), screenId);
        return true;
    }

    @Override
    public boolean updateScreen(String descriptionJson) {
        if (!supportsScreens() || TELAS_ABERTAS.get(player.getUuid()) == null) return false;

        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player,
                new dev.lualoader.network.ScreenPayloads.UpdateScreen(descriptionJson));
        return true;
    }

    @Override
    public void closeScreen() {
        TELAS_ABERTAS.remove(player.getUuid());
        if (!supportsScreens()) return;

        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player,
                new dev.lualoader.network.ScreenPayloads.CloseScreen(""));
    }

    @Override
    public String openScreenId() {
        return TELAS_ABERTAS.get(player.getUuid());
    }

    /** Chamado quando o cliente avisa que fechou a tela. */
    public static void forgetScreen(java.util.UUID playerId) {
        TELAS_ABERTAS.remove(playerId);
    }

    @Override
    public boolean setHud(String descriptionJson) {
        if (!supportsScreens()) return false;

        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player,
                new dev.lualoader.network.ScreenPayloads.SetHud(descriptionJson));
        return true;
    }

    @Override
    public boolean setOverlay(String overlayId, String descriptionJson) {
        if (!supportsScreens()) {
            dev.lualoader.LuaLoaderMod.LOGGER.warn(
                    "Cliente de {} nao registrou o canal de telas; a sobreposicao {} nao foi enviada",
                    player.getName().getString(), overlayId);
            return false;
        }
        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player,
                new dev.lualoader.network.ScreenPayloads.SetOverlay(
                        dev.lualoader.ui.ScreenProtocol.VERSION, overlayId, descriptionJson));
        return true;
    }

    @Override
    public boolean clearOverlay(String overlayId) {
        if (!supportsScreens()) return false;

        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player,
                new dev.lualoader.network.ScreenPayloads.ClearOverlay(overlayId));
        return true;
    }

    /** Tamanho de tela informado por cada cliente, em unidades de interface. */
    private static final java.util.Map<java.util.UUID, int[]> TAMANHOS_DE_TELA =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Chamado quando o cliente informa o tamanho, ao entrar e a cada mudanca de escala. */
    public static void rememberScreenSize(java.util.UUID playerId, int width, int height) {
        TAMANHOS_DE_TELA.put(playerId, new int[]{width, height});
    }

    public static void forgetScreenSize(java.util.UUID playerId) {
        TAMANHOS_DE_TELA.remove(playerId);
    }

    @Override
    public int[] screenSize() {
        int[] size = TAMANHOS_DE_TELA.get(player.getUuid());
        return size == null ? null : size.clone();
    }

    private static Item resolveItem(String itemId) {
        int separator = itemId.indexOf(':');
        if (separator <= 0 || separator == itemId.length() - 1) {
            throw new BridgeException("identificador de item invalido: " + itemId);
        }
        Identifier id = Identifier.of(itemId.substring(0, separator), itemId.substring(separator + 1));
        if (!Registries.ITEM.containsId(id)) {
            throw new BridgeException("item desconhecido: " + itemId);
        }
        return Registries.ITEM.get(id);
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
        var hunger = player.getHungerManager();
        return new float[]{hunger.getFoodLevel(), hunger.getSaturationLevel()};
    }

    @Override
    public void setFood(int level, float saturation) {
        var hunger = player.getHungerManager();
        hunger.setFoodLevel(Math.max(0, Math.min(20, level)));
        hunger.setSaturationLevel(saturation);
    }

    @Override
    public float[] experience() {
        return new float[]{player.experienceLevel, player.experienceProgress};
    }

    @Override
    public void giveExperienceLevels(int levels) {
        player.addExperienceLevels(levels);
    }

    @Override
    public String gameMode() {
        return player.interactionManager.getGameMode().getName();
    }

    @Override
    public void setGameMode(String mode) {
        var found = net.minecraft.world.GameMode.byName(mode, null);
        if (found == null) throw new BridgeException("modo de jogo desconhecido: " + mode);
        player.changeGameMode(found);
    }

    @Override
    public String dimension() {
        return player.getWorld().getRegistryKey().getValue().toString();
    }

    @Override
    public void applyEffect(String effectId, int duration, int amplifier) {
        Identifier id = Identifier.tryParse(effectId);
        if (id == null) throw new BridgeException("identificador de efeito invalido: " + effectId);

        var effect = Registries.STATUS_EFFECT.getEntry(id).orElse(null);
        if (effect == null) throw new BridgeException("efeito desconhecido: " + effectId);

        player.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(
                effect, duration, amplifier));
    }

    @Override
    public void clearEffects() {
        player.clearStatusEffects();
    }

    @Override
    public java.util.List<ActiveEffect> activeEffects() {
        java.util.List<ActiveEffect> result = new java.util.ArrayList<>();
        for (var effect : player.getStatusEffects()) {
            Identifier id = Registries.STATUS_EFFECT.getId(effect.getEffectType().value());
            if (id == null) continue;
            result.add(new ActiveEffect(id.toString(), effect.getDuration(), effect.getAmplifier(),
                    effect.isAmbient(), effect.shouldShowParticles()));
        }
        return java.util.List.copyOf(result);
    }

    @Override
    public Movement movement() {
        var velocity = player.getVelocity();
        return new Movement(velocity.x, velocity.y, velocity.z,
                player.isOnGround(), player.isSneaking(), player.isSprinting(),
                player.isSwimming(), player.getAbilities().flying, player.isFallFlying());
    }

    // ------------------------------------------------------------------ feedback

    @Override
    public void showTitle(String title, String subtitle, int fadeIn, int stay, int fadeOut) {
        // Os tempos vao antes do texto: enviados depois, so valeriam no proximo titulo, e o
        // primeiro apareceria com a duracao anterior.
        if (fadeIn >= 0 || stay >= 0 || fadeOut >= 0) {
            player.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play
                    .TitleFadeS2CPacket(
                    fadeIn < 0 ? 10 : fadeIn, stay < 0 ? 70 : stay, fadeOut < 0 ? 20 : fadeOut));
        }
        if (subtitle != null && !subtitle.isBlank()) {
            player.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play
                    .SubtitleS2CPacket(Text.literal(subtitle)));
        }
        player.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play
                .TitleS2CPacket(Text.literal(title == null ? "" : title)));
    }

    @Override
    public void playSoundTo(String soundId, float volume, float pitch) {
        Identifier id = Identifier.tryParse(soundId);
        if (id == null) throw new BridgeException("identificador de som invalido: " + soundId);

        var sound = Registries.SOUND_EVENT.getEntry(id).orElse(null);
        if (sound == null) throw new BridgeException("som desconhecido: " + soundId);

        // Na posicao do jogador, e nao no mundo: o som acompanha quem o recebeu, que e o que
        // distingue um retorno de interface de um ruido do ambiente.
        player.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play
                .PlaySoundS2CPacket(sound, net.minecraft.sound.SoundCategory.PLAYERS,
                player.getX(), player.getY(), player.getZ(), volume, pitch,
                player.getWorld().getRandom().nextLong()));
    }

    // ------------------------------------------------------------------ inventário

    @Override
    public java.util.List<String> inventory() {
        java.util.List<String> contents = new java.util.ArrayList<>();
        var inventory = player.getInventory();

        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty()) continue;

            Identifier id = Registries.ITEM.getId(stack.getItem());
            contents.add(slot + ";" + (id == null ? "minecraft:air" : id) + ";" + stack.getCount());
        }
        return contents;
    }

    @Override
    public void clearInventory() {
        player.getInventory().clear();
    }
}
