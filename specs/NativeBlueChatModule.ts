import {TurboModule, TurboModuleRegistry} from 'react-native';

export interface Spec extends TurboModule {
  readonly getDeviceId: () => string;
  readonly getDeviceName: () => string;
  readonly getDeviceType: () => string;
  readonly getDeviceOS: () => string;
  readonly getDeviceOSVersion: () => string;
  readonly getDeviceOSBuild: () => string;
  readonly getDeviceOSBuildVersion: () => string;
}

export default TurboModuleRegistry.getEnforcing<Spec>(
  'NativeBlueChatModule',
);
