import {
  DefaultTheme,
  NavigationContainer,
  type Theme,
} from '@react-navigation/native';
import {
  createNativeStackNavigator,
  type NativeStackScreenProps,
} from '@react-navigation/native-stack';
import React, { useEffect } from 'react';
import { TurboModuleRegistry } from 'react-native';
import type { RootStackParamList } from '../interface';
import BluetoothSearchScreen from '../screen/BluetoothSearchScreen';
import ChatScreen from '../screen/ChatScreen';
import HomeScreen from '../screen/HomeScreen';
import type { Spec } from '../specs/NativeBluetoothScanner';
import { peerDevice } from '../utils/device';
import { navigationRef } from './navigationRef';

const NativeModule = TurboModuleRegistry.get<Spec>('BluetoothScanner');

const Stack = createNativeStackNavigator<RootStackParamList>();

const navigationTheme: Theme = {
  ...DefaultTheme,
  dark: true,
  colors: {
    ...DefaultTheme.colors,
    primary: '#2F80ED',
    background: '#0E1116',
    card: '#0E1116',
    text: '#F5F7FA',
    border: '#1B2330',
    notification: '#2F80ED',
  },
};

type HomeProps = NativeStackScreenProps<RootStackParamList, 'Home'>;
type SearchProps = NativeStackScreenProps<RootStackParamList, 'Search'>;
type ChatProps = NativeStackScreenProps<RootStackParamList, 'Chat'>;

function HomeRoute({ navigation }: HomeProps) {
  return (
    <HomeScreen
      onNewChat={() => navigation.navigate('Search')}
      onOpenChat={deviceId =>
        navigation.navigate('Chat', {
          device: peerDevice(deviceId),
          initiator: true,
        })
      }
    />
  );
}

function SearchRoute({ navigation }: SearchProps) {
  return (
    <BluetoothSearchScreen
      onBack={() => navigation.goBack()}
      onSelectDevice={device =>
        navigation.replace('Chat', { device, initiator: true })
      }
    />
  );
}

function ChatRoute({ navigation, route }: ChatProps) {
  return (
    <ChatScreen
      device={route.params.device}
      initiator={route.params.initiator}
      onBack={() => navigation.goBack()}
    />
  );
}

function RootNavigator() {
  useEffect(() => {
    if (!NativeModule) {
      return;
    }
    const subscription = NativeModule.onConnectionStateChanged(state => {
      if (
        state.connected &&
        navigationRef.isReady() &&
        navigationRef.getCurrentRoute()?.name !== 'Chat'
      ) {
        navigationRef.navigate('Chat', {
          device: peerDevice(state.deviceId),
          initiator: false,
        });
      }
    });
    return () => subscription.remove();
  }, []);

  return (
    <NavigationContainer ref={navigationRef} theme={navigationTheme}>
      <Stack.Navigator
        screenOptions={{
          headerShown: false,
          contentStyle: { backgroundColor: '#0E1116' },
        }}>
        <Stack.Screen name="Home" component={HomeRoute} />
        <Stack.Screen name="Search" component={SearchRoute} />
        <Stack.Screen name="Chat" component={ChatRoute} />
      </Stack.Navigator>
    </NavigationContainer>
  );
}

export default RootNavigator;
