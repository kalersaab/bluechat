#import "BluetoothScanner.h"

#import <CoreBluetooth/CoreBluetooth.h>

@interface BluetoothScanner () <CBCentralManagerDelegate>
@end

@implementation BluetoothScanner {
  CBCentralManager *_centralManager;
  BOOL _isScanning;
  BOOL _wantsScan;
}

RCT_EXPORT_MODULE(BluetoothScanner)

+ (BOOL)requiresMainQueueSetup
{
  return NO;
}

- (instancetype)init
{
  if (self = [super init]) {
    _isScanning = NO;
    _wantsScan = NO;
  }
  return self;
}

- (void)dealloc
{
  _centralManager.delegate = nil;
}

- (std::shared_ptr<facebook::react::TurboModule>)getTurboModule:
    (const facebook::react::ObjCTurboModule::InitParams &)params
{
  return std::make_shared<facebook::react::NativeBluetoothScannerSpecJSI>(params);
}

#pragma mark - Spec

- (void)startBluetoothSearch
{
  dispatch_async(dispatch_get_main_queue(), ^{
    self->_wantsScan = YES;
    if (self->_centralManager == nil) {
      self->_centralManager = [[CBCentralManager alloc] initWithDelegate:self
                                                                  queue:dispatch_get_main_queue()];
    } else if (self->_centralManager.state == CBManagerStatePoweredOn) {
      [self startScanInternal];
    }
  });
}

- (void)stopBluetoothSearch
{
  dispatch_async(dispatch_get_main_queue(), ^{
    self->_wantsScan = NO;
    [self stopScanInternal];
  });
}

- (NSNumber *)isBluetoothSearching
{
  return @(_isScanning);
}

#pragma mark - Internal

- (void)startScanInternal
{
  if (_centralManager == nil || _centralManager.state != CBManagerStatePoweredOn || _isScanning) {
    return;
  }
  _isScanning = YES;
  [_centralManager scanForPeripheralsWithServices:nil options:nil];
  [self emitOnSearchStateChanged:YES];
}

- (void)stopScanInternal
{
  if (_centralManager != nil) {
    [_centralManager stopScan];
  }
  BOOL wasScanning = _isScanning;
  _isScanning = NO;
  if (wasScanning) {
    [self emitOnSearchStateChanged:NO];
  }
}

#pragma mark - CBCentralManagerDelegate

- (void)centralManagerDidUpdateState:(CBCentralManager *)central
{
  if (central.state == CBManagerStatePoweredOn) {
    if (_wantsScan) {
      [self startScanInternal];
    }
  } else if (_isScanning) {
    [self stopScanInternal];
  } else if (_wantsScan) {
    [self emitOnSearchStateChanged:NO];
  }
}

- (void)centralManager:(CBCentralManager *)central
    didDiscoverPeripheral:(CBPeripheral *)peripheral
        advertisementData:(NSDictionary<NSString *, id> *)advertisementData
                     RSSI:(NSNumber *)RSSI
{
  NSString *name = peripheral.name;
  if (name.length == 0) {
    name = advertisementData[CBAdvertisementDataLocalNameKey];
  }

  [self emitOnDeviceFound:@{
    @"id" : peripheral.identifier.UUIDString,
    @"name" : name.length > 0 ? name : @"Unknown Device",
    @"rssi" : RSSI != nil ? RSSI : @(0),
    @"paired" : @(NO),
  }];
}

@end
