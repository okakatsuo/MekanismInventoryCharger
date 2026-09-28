package net.okakatsuo.mekanisminventorycharger;

import mekanism.api.Action;
import mekanism.api.energy.IStrictEnergyHandler;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChargerSettingsTest {
    @Test void defaultsAndRoundTrip() {
        ChargerSettings settings = new ChargerSettings();
        assertNull(settings.validate());
        settings.filters.addAll(List.of("@mekanism", "#c:tools", "mekanism:meka_tool"));
        settings.enabled = true;
        settings.start = 75;
        ChargerSettings restored = ChargerSettings.load(settings.save());
        assertEquals(settings.save(), restored.save());
        assertNull(restored.validate());
    }

    @Test void invalidValuesAreRejected() {
        ChargerSettings settings = new ChargerSettings();
        settings.start = 100;
        assertNotNull(settings.validate());
        settings.start = 90;
        settings.stop = 90;
        assertNotNull(settings.validate());
        settings.stop = 100;
        settings.interval = 0;
        assertNotNull(settings.validate());
        settings.interval = 5;
        settings.filters.add("bad selector!");
        assertNotNull(settings.validate());
    }

    @Test void selectorSyntax() {
        assertTrue(ChargerSettings.validSelector("mekanism:meka_tool"));
        assertTrue(ChargerSettings.validSelector("@mekanism"));
        assertTrue(ChargerSettings.validSelector("#c:tools"));
        assertFalse(ChargerSettings.validSelector("bad selector"));
        assertFalse(ChargerSettings.validSelector("@Foo"));
        assertFalse(ChargerSettings.validSelector("#"));
    }

    @Test void networkSettingsRejectMalformedEnumsAndUnknownSchema() {
        CompoundTag packet = new ChargerSettings().save();
        assertNull(ChargerSettings.validateNetwork(packet));
        packet.putString("distribution", "FAKE");
        assertNotNull(ChargerSettings.validateNetwork(packet));
        packet.putString("distribution", "PRIORITY");
        packet.putInt("schemaVersion", 2);
        assertNotNull(ChargerSettings.validateNetwork(packet));
    }

    @Test void energyArithmeticCannotOverflow() {
        assertEquals(Long.MAX_VALUE, ChargeEngine.saturatedMultiply(Long.MAX_VALUE, 200));
        assertEquals(5_000_000, ChargeEngine.saturatedMultiply(1_000_000, 5));
        assertEquals(1, ChargeEngine.stopAmount(1, 99));
        assertEquals(0, ChargeEngine.percent(1, 10));
        assertEquals(Long.MAX_VALUE, ChargeEngine.stopAmount(Long.MAX_VALUE, 100));
    }

    @Test void transferConservesEnergyAndRespectsCapacity() {
        FakeEnergy source = new FakeEnergy(100, 100);
        FakeEnergy target = new FakeEnergy(0, 40);
        ChargeEngine.Move result = ChargeEngine.transfer(source, target, 80);
        assertEquals(40, result.inserted());
        assertEquals(60, source.stored);
        assertEquals(40, target.stored);
    }

    @Test void unexpectedTargetRejectionIsRefunded() {
        FakeEnergy source = new FakeEnergy(100, 100);
        FakeEnergy target = new FakeEnergy(0, 100) {
            @Override public long insertEnergy(int container, long amount, Action action) {
                return action == Action.EXECUTE ? amount : super.insertEnergy(container, amount, action);
            }
        };
        ChargeEngine.Move result = ChargeEngine.transfer(source, target, 50);
        assertEquals(0, result.inserted());
        assertEquals(100, source.stored);
        assertEquals(0, target.stored);
    }

    private static class FakeEnergy implements IStrictEnergyHandler {
        long stored;
        final long capacity;
        FakeEnergy(long stored, long capacity) { this.stored = stored; this.capacity = capacity; }
        @Override public int getEnergyContainerCount() { return 1; }
        @Override public long getEnergy(int container) { return stored; }
        @Override public void setEnergy(int container, long value) { stored = value; }
        @Override public long getMaxEnergy(int container) { return capacity; }
        @Override public long getNeededEnergy(int container) { return capacity - stored; }
        @Override public long insertEnergy(int container, long amount, Action action) {
            long accepted = Math.min(amount, capacity - stored);
            if (action == Action.EXECUTE) stored += accepted;
            return amount - accepted;
        }
        @Override public long extractEnergy(int container, long amount, Action action) {
            long extracted = Math.min(amount, stored);
            if (action == Action.EXECUTE) stored -= extracted;
            return extracted;
        }
    }
}
