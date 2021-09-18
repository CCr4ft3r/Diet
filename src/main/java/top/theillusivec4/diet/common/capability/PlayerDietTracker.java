/*
 * Copyright (C) 2021 C4
 *
 * This file is part of Diet, a mod made for Minecraft.
 *
 * Diet is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser
 * General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * Diet is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the
 * implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General
 * Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License along with Diet.
 * If not, see <https://www.gnu.org/licenses/>.
 *
 */

package top.theillusivec4.diet.common.capability;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Sets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.entity.ai.attributes.Attribute;
import net.minecraft.entity.ai.attributes.AttributeModifier;
import net.minecraft.entity.ai.attributes.ModifiableAttributeInstance;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.Effect;
import net.minecraft.potion.EffectInstance;
import net.minecraft.potion.Effects;
import net.minecraft.util.FoodStats;
import net.minecraft.util.math.MathHelper;
import net.minecraftforge.common.MinecraftForge;
import top.theillusivec4.diet.api.DietApi;
import top.theillusivec4.diet.api.DietEvent;
import top.theillusivec4.diet.api.IDietGroup;
import top.theillusivec4.diet.api.IDietResult;
import top.theillusivec4.diet.api.IDietTracker;
import top.theillusivec4.diet.common.config.DietServerConfig;
import top.theillusivec4.diet.common.effect.DietEffect;
import top.theillusivec4.diet.common.effect.DietEffects;
import top.theillusivec4.diet.common.effect.DietEffectsInfo;
import top.theillusivec4.diet.common.group.DietGroups;
import top.theillusivec4.diet.common.network.DietNetwork;
import top.theillusivec4.diet.common.util.DietResult;

public class PlayerDietTracker implements IDietTracker {

  private static final Map<Effect, Integer> EFFECT_DURATION = new HashMap<>();

  private final PlayerEntity player;
  private final Map<String, Float> values = new HashMap<>();
  private final Map<Attribute, Set<UUID>> activeModifiers = new HashMap<>();
  private final Set<Item> eatenFood = new HashSet<>();

  private boolean active = true;

  private int prevFood;
  private float prevSaturation;
  private ItemStack captured = ItemStack.EMPTY;

  static {
    EFFECT_DURATION.put(Effects.NIGHT_VISION, 300);
    EFFECT_DURATION.put(Effects.NAUSEA, 300);
  }

  public PlayerDietTracker(PlayerEntity playerIn) {
    player = playerIn;
    FoodStats stats = playerIn.getFoodStats();
    prevFood = stats.getFoodLevel();
    prevSaturation = stats.getSaturationLevel();
    values.clear();

    for (IDietGroup group : DietGroups.get()) {
      String name = group.getName();
      float amount = group.getDefaultValue();
      values.put(name, MathHelper.clamp(amount, 0.0f, 1.0f));
    }
  }

  @Override
  public void tick() {

    if (player instanceof ServerPlayerEntity) {

      if (!player.isCreative() && active) {
        int currentFood = player.getFoodStats().getFoodLevel();
        float currentSaturation = player.getFoodStats().getSaturationLevel();

        if (currentFood < prevFood) {

          if (!MinecraftForge.EVENT_BUS.post(new DietEvent.ApplyDecay(player))) {
            decay(prevFood - currentFood);
          }
        } else if (currentFood > prevFood && !captured.isEmpty()) {
          int healing = currentFood - prevFood;
          float saturationModifier = (currentSaturation - prevSaturation) / healing;
          consume(captured, healing, saturationModifier);
          captured = ItemStack.EMPTY;
        }
        prevFood = currentFood;
        prevSaturation = currentSaturation;
      }

      if (player.ticksExisted % 80 == 0) {
        for (Map.Entry<Attribute, Set<UUID>> entry : activeModifiers.entrySet()) {
          Set<UUID> uuids = entry.getValue();
          ModifiableAttributeInstance att = player.getAttribute(entry.getKey());

          if (att != null) {

            for (UUID uuid : uuids) {
              att.removeModifier(uuid);
            }
          }
        }
        activeModifiers.clear();

        if (active) {
          applyEffects();
        }
      }
    }
  }

  @Override
  public void consume(ItemStack stack, int healing, float saturationModifier) {

    if (active && prevFood != player.getFoodStats().getFoodLevel() &&
        !MinecraftForge.EVENT_BUS.post(new DietEvent.ConsumeItemStack(stack, player))) {
      IDietResult result = DietApi.getInstance().get(player, stack, healing, saturationModifier);

      if (result != DietResult.EMPTY) {
        addEaten(stack.getItem());
        apply(result);
      }
    }
  }

  @Override
  public void consume(ItemStack stack) {

    if (active && prevFood != player.getFoodStats().getFoodLevel() &&
        !MinecraftForge.EVENT_BUS.post(new DietEvent.ConsumeItemStack(stack, player))) {
      IDietResult result = DietApi.getInstance().get(player, stack);

      if (result != DietResult.EMPTY) {
        addEaten(stack.getItem());
        apply(result);
      }
    }
  }

  @Override
  public float getValue(String group) {
    return values.getOrDefault(group, 0.0f);
  }

  @Override
  public void setValue(String group, float amount) {
    values.put(group, MathHelper.clamp(amount, 0.0f, 1.0f));
  }

  @Override
  public Map<String, Float> getValues() {
    return ImmutableMap.copyOf(values);
  }

  @Override
  public void setValues(Map<String, Float> entries) {
    values.clear();
    values.putAll(entries);
  }

  @Override
  public Map<Attribute, Set<UUID>> getModifiers() {
    return ImmutableMap.copyOf(activeModifiers);
  }

  @Override
  public void setModifiers(Map<Attribute, Set<UUID>> modifiers) {
    activeModifiers.clear();
    activeModifiers.putAll(modifiers);
  }

  @Override
  public boolean isActive() {
    return active;
  }

  @Override
  public void setActive(boolean active) {
    this.active = active;
  }

  @Override
  public PlayerEntity getPlayer() {
    return player;
  }

  private void applyEffects() {

    if (MinecraftForge.EVENT_BUS.post(new DietEvent.ApplyEffect(getPlayer()))) {
      return;
    }

    List<DietEffect> effects = DietEffects.get();
    DietEffectsInfo info = new DietEffectsInfo();

    for (DietEffect effect : effects) {
      boolean match = true;
      int multiplier = 0;

      for (DietEffect.Condition condition : effect.conditions) {
        int matches = condition.getMatches(player, values);

        if (matches == 0) {
          match = false;
          break;
        }

        if (condition.match == DietEffect.MatchMethod.EVERY) {
          multiplier += matches;
        }
      }

      if (match) {
        multiplier = Math.max(1, multiplier);

        for (DietEffect.DietAttribute attribute : effect.attributes) {
          ModifiableAttributeInstance att = player.getAttribute(attribute.attribute);
          AttributeModifier mod =
              new AttributeModifier(effect.uuid, "Diet group effect", attribute.amount * multiplier,
                  attribute.operation);

          if (att != null && !att.hasModifier(mod)) {
            att.applyPersistentModifier(mod);
            activeModifiers.computeIfAbsent(attribute.attribute, k -> new HashSet<>())
                .add(effect.uuid);
            info.addModifier(attribute.attribute, mod);
          }
        }

        for (DietEffect.DietStatusEffect statusEffect : effect.statusEffects) {
          int duration = EFFECT_DURATION.getOrDefault(statusEffect.effect, 100);
          EffectInstance instance =
              new EffectInstance(statusEffect.effect, duration, statusEffect.power * multiplier,
                  true, false);
          player.addPotionEffect(instance);
          info.addEffect(instance);
        }
      }
    }

    if (player instanceof ServerPlayerEntity) {
      DietNetwork.sendEffectsInfoS2C((ServerPlayerEntity) player, info);
    }
  }

  private void decay(int foodDiff) {
    Map<String, Float> updated = new HashMap<>();
    long size = values.values().stream().filter(val -> val > 0.0f).count();

    if (size <= 0) {
      return;
    }
    float scale = ((float) foodDiff) / size;
    scale *= Math.pow(1.0f - DietServerConfig.decayPenaltyPerGroup, size - 1);

    for (IDietGroup group : DietGroups.get()) {
      String name = group.getName();
      float value = getValue(name);
      float decay = (float) (Math.exp(value) * scale * group.getDecayMultiplier() / 100.0f);

      if (decay > 0.0f) {
        value = MathHelper.clamp(value - decay, 0.0f, 1.0f);
        values.replace(name, value);
        updated.put(name, value);
      }
    }

    if (!updated.isEmpty()) {
      sync(updated);
    }
  }

  private void apply(IDietResult result) {
    Map<IDietGroup, Float> entries = result.get();
    Map<String, Float> applied = new HashMap<>();

    for (Map.Entry<IDietGroup, Float> entry : entries.entrySet()) {
      String name = entry.getKey().getName();
      float value = MathHelper.clamp(entry.getValue() + values.get(name), 0.0f, 1.0f);
      values.replace(name, value);
      applied.put(name, value);
    }

    if (!applied.isEmpty()) {
      sync(applied);
    }
  }

  @Override
  public void sync() {
    sync(values);
    sync(active);
    sync(eatenFood);
  }

  private void sync(Set<Item> values) {

    if (player instanceof ServerPlayerEntity) {
      DietNetwork.sendEatenS2C((ServerPlayerEntity) player, values);
    }
  }

  private void sync(Map<String, Float> values) {

    if (player instanceof ServerPlayerEntity) {
      DietNetwork.sendDietS2C((ServerPlayerEntity) player, values);
    }
  }

  private void sync(boolean flag) {

    if (player instanceof ServerPlayerEntity) {
      DietNetwork.sendActivationS2C((ServerPlayerEntity) player, flag);
    }
  }

  @Override
  public void captureStack(ItemStack stack) {
    captured = stack;
  }

  @Override
  public ItemStack getCapturedStack() {
    return captured;
  }

  @Override
  public void addEaten(Item item) {
    eatenFood.add(item);
    sync(Sets.newHashSet(item));
  }

  @Override
  public Set<Item> getEaten() {
    return eatenFood;
  }

  @Override
  public void setEaten(Set<Item> foods) {
    eatenFood.clear();
    eatenFood.addAll(foods);
  }
}
