#import "BluetoothScanner.h"

#import <CoreBluetooth/CoreBluetooth.h>
#import <UIKit/UIKit.h>

static CBUUID *BlueChatServiceUUID(void)
{
  static CBUUID *uuid = nil;
  static dispatch_once_t onceToken;
  dispatch_once(&onceToken, ^{
    uuid = [CBUUID UUIDWithString:@"6E400001-B5A3-F393-E0A9-E50E24DCCA9E"];
  });
  return uuid;
}

static CBUUID *BlueChatRxUUID(void)
{
  static CBUUID *uuid = nil;
  static dispatch_once_t onceToken;
  dispatch_once(&onceToken, ^{
    uuid = [CBUUID UUIDWithString:@"6E400002-B5A3-F393-E0A9-E50E24DCCA9E"];
  });
  return uuid;
}

static CBUUID *BlueChatTxUUID(void)
{
  static CBUUID *uuid = nil;
  static dispatch_once_t onceToken;
  dispatch_once(&onceToken, ^{
    uuid = [CBUUID UUIDWithString:@"6E400003-B5A3-F393-E0A9-E50E24DCCA9E"];
  });
  return uuid;
}

@interface BluetoothScanner () <CBCentralManagerDelegate, CBPeripheralDelegate, CBPeripheralManagerDelegate>
@end

@implementation BluetoothScanner {
  CBCentralManager *_centralManager;
  CBPeripheralManager *_peripheralManager;

  NSMutableDictionary<NSString *, CBPeripheral *> *_discoveredPeripherals;
  CBPeripheral *_connectedPeripheral;
  CBCharacteristic *_clientRx;
  CBCharacteristic *_clientTx;
  NSUInteger _clientChunkSize;
  BOOL _writeInProgress;
  NSMutableArray<NSData *> *_clientQueue;

  CBMutableCharacteristic *_serverRx;
  CBMutableCharacteristic *_serverTx;
  CBCentral *_subscribedCentral;
  BOOL _servicesAdded;
  NSMutableArray<NSData *> *_serverQueue;

  NSMutableData *_incomingBuffer;
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
    _clientChunkSize = 20;
    _discoveredPeripherals = [NSMutableDictionary dictionary];
    _clientQueue = [NSMutableArray array];
    _serverQueue = [NSMutableArray array];
    _incomingBuffer = [NSMutableData data];
  }
  return self;
}

- (void)dealloc
{
  _centralManager.delegate = nil;
  _peripheralManager.delegate = nil;
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
    [self ensurePeripheralManager];
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

- (void)connect:(NSString *)deviceId
{
  dispatch_async(dispatch_get_main_queue(), ^{
    [self ensurePeripheralManager];

    CBPeripheral *peripheral = self->_discoveredPeripherals[deviceId];
    if (peripheral == nil) {
      NSUUID *uuid = [[NSUUID alloc] initWithUUIDString:deviceId];
      if (uuid != nil && self->_centralManager != nil) {
        peripheral = [self->_centralManager retrievePeripheralsWithIdentifiers:@[ uuid ]].firstObject;
      }
    }
    if (peripheral == nil) {
      return;
    }

    [self teardownClient];
    peripheral.delegate = self;
    self->_connectedPeripheral = peripheral;
    if (self->_centralManager == nil) {
      self->_centralManager = [[CBCentralManager alloc] initWithDelegate:self
                                                                  queue:dispatch_get_main_queue()];
    }
    [self->_centralManager connectPeripheral:peripheral options:nil];
  });
}

- (void)disconnect
{
  dispatch_async(dispatch_get_main_queue(), ^{
    [self teardownClient];
  });
}

- (void)sendMessage:(NSString *)text
{
  dispatch_async(dispatch_get_main_queue(), ^{
    NSData *framed = [self frameMessage:text];
    [self enqueueFramed:framed];
  });
}

#pragma mark - Server (peripheral role)

- (void)ensurePeripheralManager
{
  if (_peripheralManager == nil) {
    _peripheralManager = [[CBPeripheralManager alloc] initWithDelegate:self
                                                                 queue:dispatch_get_main_queue()];
  } else if (_peripheralManager.state == CBManagerStatePoweredOn) {
    [self setupServiceIfNeeded];
  }
}

- (void)setupServiceIfNeeded
{
  if (_servicesAdded) {
    [self startAdvertising];
    return;
  }
  _serverRx = [[CBMutableCharacteristic alloc] initWithType:BlueChatRxUUID()
                                               properties:CBCharacteristicPropertyWrite |
                                                          CBCharacteristicPropertyWriteWithoutResponse
                                                    value:nil
                                              permissions:CBAttributePermissionsWriteable];
  _serverTx = [[CBMutableCharacteristic alloc] initWithType:BlueChatTxUUID()
                                                properties:CBCharacteristicPropertyNotify
                                                     value:nil
                                               permissions:CBAttributePermissionsReadable];
  CBMutableService *service = [[CBMutableService alloc] initWithType:BlueChatServiceUUID() primary:YES];
  service.characteristics = @[ _serverRx, _serverTx ];
  [_peripheralManager addService:service];
  _servicesAdded = YES;
}

- (void)startAdvertising
{
  if (_peripheralManager.state != CBManagerStatePoweredOn) {
    return;
  }
  NSString *name = UIDevice.currentDevice.name;
  [_peripheralManager startAdvertising:@{
    CBAdvertisementDataServiceUUIDsKey : @[ BlueChatServiceUUID() ],
    CBAdvertisementDataLocalNameKey : name.length > 0 ? name : @"bluechat",
  }];
}

#pragma mark - Framing

- (NSData *)frameMessage:(NSString *)text
{
  NSData *payload = [text dataUsingEncoding:NSUTF8StringEncoding];
  uint32_t length = (uint32_t)payload.length;
  NSMutableData *framed = [NSMutableData dataWithCapacity:4 + payload.length];
  uint8_t header[4] = {
    (uint8_t)((length >> 24) & 0xFF),
    (uint8_t)((length >> 16) & 0xFF),
    (uint8_t)((length >> 8) & 0xFF),
    (uint8_t)(length & 0xFF),
  };
  [framed appendBytes:header length:4];
  [framed appendData:payload];
  return framed;
}

- (void)enqueueFramed:(NSData *)framed
{
  NSUInteger chunkSize;
  NSMutableArray<NSData *> *queue;

  if (_subscribedCentral != nil && _serverTx != nil) {
    NSUInteger maximum = MAX((NSUInteger)20, _subscribedCentral.maximumUpdateValueLength);
    chunkSize = MIN(maximum, (NSUInteger)180);
    queue = _serverQueue;
  } else if (_connectedPeripheral != nil && _clientRx != nil) {
    chunkSize = _clientChunkSize > 0 ? _clientChunkSize : 20;
    queue = _clientQueue;
  } else {
    return;
  }

  NSUInteger offset = 0;
  while (offset < framed.length) {
    NSUInteger end = MIN(offset + chunkSize, framed.length);
    [queue addObject:[framed subdataWithRange:NSMakeRange(offset, end - offset)]];
    offset = end;
  }
  [self sendNextChunk];
}

- (void)sendNextChunk
{
  if (_subscribedCentral != nil && _serverTx != nil) {
    if (_serverQueue.count == 0) {
      return;
    }
    NSData *chunk = _serverQueue.firstObject;
    BOOL sent = [_peripheralManager updateValue:chunk
                              forCharacteristic:_serverTx
                           onSubscribedCentrals:@[ _subscribedCentral ]];
    if (sent) {
      [_serverQueue removeObjectAtIndex:0];
      __weak BluetoothScanner *weakSelf = self;
      dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(0.03 * NSEC_PER_SEC)),
                     dispatch_get_main_queue(), ^{
                       [weakSelf sendNextChunk];
                     });
    }
    return;
  }

  if (_connectedPeripheral != nil && _clientRx != nil) {
    if (_writeInProgress || _clientQueue.count == 0) {
      return;
    }
    NSData *chunk = _clientQueue.firstObject;
    [_clientQueue removeObjectAtIndex:0];
    _writeInProgress = YES;
    [_connectedPeripheral writeValue:chunk
                   forCharacteristic:_clientRx
                                type:CBCharacteristicWriteWithResponse];
  }
}

- (void)handleIncomingData:(NSData *)data deviceId:(NSString *)deviceId
{
  if (data.length == 0) {
    return;
  }
  [_incomingBuffer appendData:data];
  const uint8_t *bytes = (const uint8_t *)_incomingBuffer.bytes;
  NSUInteger total = _incomingBuffer.length;
  NSUInteger offset = 0;

  while (total - offset >= 4) {
    uint32_t length = ((uint32_t)bytes[offset] << 24) |
                      ((uint32_t)bytes[offset + 1] << 16) |
                      ((uint32_t)bytes[offset + 2] << 8) |
                      (uint32_t)bytes[offset + 3];
    if (length == 0 || length > 1000000) {
      [_incomingBuffer setLength:0];
      return;
    }
    if (total - offset - 4 < length) {
      break;
    }
    NSData *payload = [_incomingBuffer subdataWithRange:NSMakeRange(offset + 4, length)];
    NSString *text = [[NSString alloc] initWithData:payload encoding:NSUTF8StringEncoding];
    offset += 4 + length;
    if (text != nil) {
      [self emitOnMessageReceived:@{@"text" : text, @"deviceId" : deviceId ?: @""}];
    }
  }

  if (offset > 0) {
    [_incomingBuffer replaceBytesInRange:NSMakeRange(0, offset) withBytes:NULL length:0];
  }
}

#pragma mark - Client (central role)

- (void)teardownClient
{
  if (_connectedPeripheral != nil) {
    _connectedPeripheral.delegate = nil;
    if (_centralManager != nil) {
      [_centralManager cancelPeripheralConnection:_connectedPeripheral];
    }
    _connectedPeripheral = nil;
  }
  _clientRx = nil;
  _clientTx = nil;
  _writeInProgress = NO;
  [_clientQueue removeAllObjects];
}

#pragma mark - Internal scan

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
  _discoveredPeripherals[peripheral.identifier.UUIDString] = peripheral;

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

- (void)centralManager:(CBCentralManager *)central didConnectPeripheral:(CBPeripheral *)peripheral
{
  _clientChunkSize = MAX((NSUInteger)20,
                         MIN((NSUInteger)180,
                             [peripheral maximumWriteValueLengthForType:CBCharacteristicWriteWithResponse]));
  [peripheral discoverServices:@[ BlueChatServiceUUID() ]];
}

- (void)centralManager:(CBCentralManager *)central
    didFailToConnectPeripheral:(CBPeripheral *)peripheral
                         error:(NSError *)error
{
  [self emitOnConnectionStateChanged:@{
    @"connected" : @(NO),
    @"deviceId" : peripheral.identifier.UUIDString,
  }];
}

- (void)centralManager:(CBCentralManager *)central
    didDisconnectPeripheral:(CBPeripheral *)peripheral
                      error:(NSError *)error
{
  if (peripheral == _connectedPeripheral) {
    _connectedPeripheral.delegate = nil;
    _connectedPeripheral = nil;
    _clientRx = nil;
    _clientTx = nil;
    _writeInProgress = NO;
    [_clientQueue removeAllObjects];
  }
  [self emitOnConnectionStateChanged:@{
    @"connected" : @(NO),
    @"deviceId" : peripheral.identifier.UUIDString,
  }];
}

#pragma mark - CBPeripheralDelegate

- (void)peripheral:(CBPeripheral *)peripheral didDiscoverServices:(NSError *)error
{
  for (CBService *service in peripheral.services) {
    if ([service.UUID isEqual:BlueChatServiceUUID()]) {
      [peripheral discoverCharacteristics:@[ BlueChatRxUUID(), BlueChatTxUUID() ] forService:service];
    }
  }
}

- (void)peripheral:(CBPeripheral *)peripheral
    didDiscoverCharacteristicsForService:(CBService *)service
                                   error:(NSError *)error
{
  if (![service.UUID isEqual:BlueChatServiceUUID()]) {
    return;
  }
  for (CBCharacteristic *characteristic in service.characteristics) {
    if ([characteristic.UUID isEqual:BlueChatRxUUID()]) {
      _clientRx = characteristic;
    } else if ([characteristic.UUID isEqual:BlueChatTxUUID()]) {
      _clientTx = characteristic;
      [peripheral setNotifyValue:YES forCharacteristic:characteristic];
    }
  }
}

- (void)peripheral:(CBPeripheral *)peripheral
    didUpdateNotificationStateForCharacteristic:(CBCharacteristic *)characteristic
                                          error:(NSError *)error
{
  if ([characteristic.UUID isEqual:BlueChatTxUUID()] && characteristic.isNotifying) {
    [self emitOnConnectionStateChanged:@{
      @"connected" : @(YES),
      @"deviceId" : peripheral.identifier.UUIDString,
    }];
  }
}

- (void)peripheral:(CBPeripheral *)peripheral
    didUpdateValueForCharacteristic:(CBCharacteristic *)characteristic
                              error:(NSError *)error
{
  if ([characteristic.UUID isEqual:BlueChatTxUUID()]) {
    [self handleIncomingData:characteristic.value deviceId:peripheral.identifier.UUIDString];
  }
}

- (void)peripheral:(CBPeripheral *)peripheral
    didWriteValueForCharacteristic:(CBCharacteristic *)characteristic
                             error:(NSError *)error
{
  _writeInProgress = NO;
  [self sendNextChunk];
}

#pragma mark - CBPeripheralManagerDelegate

- (void)peripheralManagerDidUpdateState:(CBPeripheralManager *)peripheral
{
  if (peripheral.state == CBManagerStatePoweredOn) {
    [self setupServiceIfNeeded];
  }
}

- (void)peripheralManager:(CBPeripheralManager *)peripheral didAddService:(CBService *)service error:(NSError *)error
{
  if (error == nil && [service.UUID isEqual:BlueChatServiceUUID()]) {
    [self startAdvertising];
  }
}

- (void)peripheralManager:(CBPeripheralManager *)peripheral
    didReceiveWriteRequests:(NSArray<CBATTRequest *> *)requests
{
  for (CBATTRequest *request in requests) {
    if ([request.characteristic.UUID isEqual:BlueChatRxUUID()] && request.value != nil) {
      [self handleIncomingData:request.value deviceId:request.central.identifier.UUIDString];
    }
  }
  if (requests.count > 0) {
    [peripheral respondToRequest:requests.firstObject withResult:CBATTErrorSuccess];
  }
}

- (void)peripheralManager:(CBPeripheralManager *)peripheral
                    central:(CBCentral *)central
    didSubscribeToCharacteristic:(CBCharacteristic *)characteristic
{
  if ([characteristic.UUID isEqual:BlueChatTxUUID()]) {
    _subscribedCentral = central;
    [self emitOnConnectionStateChanged:@{
      @"connected" : @(YES),
      @"deviceId" : central.identifier.UUIDString,
    }];
  }
}

- (void)peripheralManager:(CBPeripheralManager *)peripheral
                    central:(CBCentral *)central
    didUnsubscribeFromCharacteristic:(CBCharacteristic *)characteristic
{
  if ([characteristic.UUID isEqual:BlueChatTxUUID()] && central == _subscribedCentral) {
    _subscribedCentral = nil;
    [self emitOnConnectionStateChanged:@{
      @"connected" : @(NO),
      @"deviceId" : central.identifier.UUIDString,
    }];
  }
}

- (void)peripheralManagerIsReadyToUpdateSubscribers:(CBPeripheralManager *)peripheral
{
  [self sendNextChunk];
}

@end
