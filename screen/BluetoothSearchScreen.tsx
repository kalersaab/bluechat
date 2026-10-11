import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  ActivityIndicator,
  FlatList,
  ListRenderItem,
  PermissionsAndroid,
  Platform,
  Pressable,
  StyleSheet,
  Text,
  TurboModuleRegistry,
  View,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import type { BluetoothDevice, BluetoothSearchScreenProps } from '../interface';
import type { Spec } from '../specs/NativeBluetoothScanner';

const NativeModule = TurboModuleRegistry.get<Spec>('BluetoothScanner');

const requestBluetoothPermissions = async (): Promise<boolean> => {
  if (Platform.OS !== 'android') {
    return true;
  }

  const permissions =
    Number(Platform.Version) >= 31
      ? [
          PermissionsAndroid.PERMISSIONS.BLUETOOTH_SCAN,
          PermissionsAndroid.PERMISSIONS.BLUETOOTH_CONNECT,
          PermissionsAndroid.PERMISSIONS.BLUETOOTH_ADVERTISE,
        ]
      : [PermissionsAndroid.PERMISSIONS.ACCESS_FINE_LOCATION];

  const results = await PermissionsAndroid.requestMultiple(permissions);
  return permissions.every(
    permission => results[permission] === PermissionsAndroid.RESULTS.GRANTED,
  );
};

const Separator = () => <View style={styles.separator} />;

const getSignalBars = (rssi: number): string => {
  if (rssi >= -55) {
    return '▂▄▆█';
  }
  if (rssi >= -65) {
    return '▂▄▆';
  }
  if (rssi >= -75) {
    return '▂▄';
  }
  return '▂';
};

function BluetoothSearchScreen({
  onSelectDevice,
  onBack,
}: BluetoothSearchScreenProps) {
  const insets = useSafeAreaInsets();
  const [isScanning, setIsScanning] = useState(false);
  const [devices, setDevices] = useState<BluetoothDevice[]>([]);
  const fallbackTimeout = useRef<ReturnType<typeof setTimeout> | null>(null);

  const clearFallback = useCallback(() => {
    if (fallbackTimeout.current) {
      clearTimeout(fallbackTimeout.current);
      fallbackTimeout.current = null;
    }
  }, []);

  const startScan = useCallback(async () => {
    setDevices([]);
    setIsScanning(true);

    if (NativeModule) {
      const granted = await requestBluetoothPermissions();
      if (!granted) {
        setIsScanning(false);
        return;
      }
      NativeModule.startBluetoothSearch();
      return;
    }

    clearFallback();
    fallbackTimeout.current = setTimeout(() => {
      setDevices([]);
      setIsScanning(false);
      fallbackTimeout.current = null;
    }, 2000);
  }, [clearFallback]);

  const stopScan = useCallback(() => {
    NativeModule?.stopBluetoothSearch();
    clearFallback();
    setIsScanning(false);
  }, [clearFallback]);

  useEffect(() => {
    const nativeModule = NativeModule;

    if (!nativeModule) {
      startScan();
      return clearFallback;
    }

    const deviceSubscription = nativeModule.onDeviceFound(device => {
      console.log('device', device);
      setDevices(previous =>
        previous.some(item => item.id === device.id)
          ? previous
          : [...previous, device],
      );
    });
    const stateSubscription = nativeModule.onSearchStateChanged(searching =>
      setIsScanning(searching),
    );

    startScan();

    return () => {
      deviceSubscription.remove();
      stateSubscription.remove();
      nativeModule.stopBluetoothSearch();
    };
  }, [startScan, clearFallback]);

  const renderItem: ListRenderItem<BluetoothDevice> = ({ item }) => (
    <Pressable style={styles.deviceRow} onPress={() => onSelectDevice(item)}>
      <View style={styles.deviceIcon}>
        <Text style={styles.deviceIconText}>BT</Text>
      </View>
      <View style={styles.deviceInfo}>
        <Text style={styles.deviceName}>{item.name}</Text>
        <Text style={styles.deviceId}>{item.paired ? 'Paired' : item.id}</Text>
      </View>
      <Text style={styles.signal}>{getSignalBars(item.rssi)}</Text>
    </Pressable>
  );

  return (
    <View style={[styles.container, { paddingTop: insets.top }]}>
      <View style={styles.header}>
        {onBack ? (
          <Pressable
            onPress={onBack}
            style={styles.backButton}
            accessibilityRole="button"
            accessibilityLabel="Go back">
            <Text style={styles.backIcon}>‹</Text>
          </Pressable>
        ) : null}
        <View style={styles.headerText}>
          <Text style={styles.title}>Bluetooth Devices</Text>
          <Text style={styles.subtitle}>
            {isScanning
              ? 'Searching for nearby devices...'
              : `${devices.length} device${devices.length === 1 ? '' : 's'} found`}
          </Text>
        </View>
      </View>

      <FlatList
        data={devices}
        keyExtractor={item => item.id}
        renderItem={renderItem}
        contentContainerStyle={styles.listContent}
        ItemSeparatorComponent={Separator}
        ListEmptyComponent={
          isScanning ? (
            <View style={styles.empty}>
              <ActivityIndicator size="large" color="#2F80ED" />
            </View>
          ) : (
            <View style={styles.empty}>
              <Text style={styles.emptyText}>No devices found</Text>
            </View>
          )
        }
      />

      <View style={[styles.footer, { paddingBottom: insets.bottom + 16 }]}>
        <Pressable
          style={styles.scanButton}
          onPress={isScanning ? stopScan : startScan}>
          <Text style={styles.scanButtonText}>
            {isScanning ? 'Stop Scan' : 'Scan for Devices'}
          </Text>
        </Pressable>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#0E1116',
  },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 20,
    paddingTop: 16,
    paddingBottom: 12,
  },
  headerText: {
    flex: 1,
  },
  backButton: {
    width: 32,
    height: 40,
    alignItems: 'center',
    justifyContent: 'center',
    marginLeft: -8,
    marginRight: 4,
  },
  backIcon: {
    fontSize: 34,
    lineHeight: 36,
    color: '#2F80ED',
  },
  title: {
    fontSize: 28,
    fontWeight: '700',
    color: '#F5F7FA',
  },
  subtitle: {
    marginTop: 4,
    fontSize: 14,
    color: '#8A94A6',
  },
  listContent: {
    paddingHorizontal: 20,
    flexGrow: 1,
  },
  deviceRow: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingVertical: 14,
  },
  deviceIcon: {
    width: 44,
    height: 44,
    borderRadius: 22,
    backgroundColor: '#1B2330',
    alignItems: 'center',
    justifyContent: 'center',
  },
  deviceIconText: {
    color: '#2F80ED',
    fontWeight: '700',
    fontSize: 13,
  },
  deviceInfo: {
    flex: 1,
    marginLeft: 14,
  },
  deviceName: {
    fontSize: 16,
    fontWeight: '600',
    color: '#F5F7FA',
  },
  deviceId: {
    marginTop: 2,
    fontSize: 12,
    color: '#8A94A6',
  },
  signal: {
    fontSize: 14,
    color: '#2F80ED',
    letterSpacing: 1,
  },
  separator: {
    height: StyleSheet.hairlineWidth,
    backgroundColor: '#1B2330',
  },
  empty: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    paddingVertical: 48,
  },
  emptyText: {
    color: '#8A94A6',
    fontSize: 15,
  },
  footer: {
    paddingHorizontal: 20,
    paddingTop: 12,
  },
  scanButton: {
    backgroundColor: '#2F80ED',
    borderRadius: 14,
    paddingVertical: 16,
    alignItems: 'center',
  },
  scanButtonText: {
    color: '#FFFFFF',
    fontSize: 16,
    fontWeight: '600',
  },
});

export default BluetoothSearchScreen;
