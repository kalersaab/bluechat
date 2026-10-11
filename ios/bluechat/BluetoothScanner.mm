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

  CBMutableCharacteristic *_serverRx;
  CBMutableCharacteristic *_serverTx;
  CBCentral *_subscribedCentral;
  BOOL _servicesAdded;

  NSMutableDictionary<NSString *, NSMutableArray<NSData *> *> *_outgoingQueues;
  NSMutableDictionary<NSString *, NSMutableData *> *_incomingBuffers;
  NSMutableSet<NSString *> *_pendingWrites;
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
    _outgoingQueues = [NSMutableDictionary dictionary];
    _incomingBuffers = [NSMutableDictionary dictionary];
    _pendingWrites = [NSMutableSet set];
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

- (void)sendMessage:(NSString *)deviceId
          messageId:(NSString *)messageId
               text:(NSString *)text
          timestamp:(double)timestamp
{
  dispatch_async(dispatch_get_main_queue(), ^{
    NSData *payload = [self encodeMessagePacket:messageId text:text timestamp:timestamp];
    [self enqueueFramed:[self framePayload:payload] forDevice:deviceId];
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

- (NSData *)encodeMessagePacket:(NSString *)messageId
                            text:(NSString *)text
                       timestamp:(double)timestamp
{
  NSMutableData *payload = [NSMutableData data];
  uint8_t type = 1;
  [payload appendBytes:&type length:1];
  [self appendString:messageId toData:payload];
  [self appendInt64:(uint64_t)timestamp toData:payload];
  [self appendString:text toData:payload];
  return payload;
}

- (NSData *)encodeAckPacket:(NSString *)messageId timestamp:(double)timestamp
{
  NSMutableData *payload = [NSMutableData data];
  uint8_t type = 2;
  [payload appendBytes:&type length:1];
  [self appendString:messageId toData:payload];
  [self appendInt64:(uint64_t)timestamp toData:payload];
  return payload;
}

- (void)appendString:(NSString *)value toData:(NSMutableData *)data
{
  NSData *bytes = [value dataUsingEncoding:NSUTF8StringEncoding] ?: [NSData data];
  uint16_t length = (uint16_t)bytes.length;
  uint8_t header[2] = {
    (uint8_t)((length >> 8) & 0xFF),
    (uint8_t)(length & 0xFF),
  };
  [data appendBytes:header length:2];
  [data appendData:bytes];
}

- (void)appendInt64:(uint64_t)value toData:(NSMutableData *)data
{
  uint8_t bytes[8];
  for (int index = 0; index < 8; index++) {
    bytes[index] = (uint8_t)((value >> (8 * (7 - index))) & 0xFF);
  }
  [data appendBytes:bytes length:8];
}

- (NSData *)framePayload:(NSData *)payload
{
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

- (NSMutableArray<NSData *> *)queueForDevice:(NSString *)deviceId
{
  NSMutableArray<NSData *> *queue = _outgoingQueues[deviceId];
  if (queue == nil) {
    queue = [NSMutableArray array];
    _outgoingQueues[deviceId] = queue;
  }
  return queue;
}

- (NSMutableData *)incomingBufferForDevice:(NSString *)deviceId
{
  NSMutableData *buffer = _incomingBuffers[deviceId];
  if (buffer == nil) {
    buffer = [NSMutableData data];
    _incomingBuffers[deviceId] = buffer;
  }
  return buffer;
}

- (void)enqueueFramed:(NSData *)framed forDevice:(NSString *)deviceId
{
  NSUInteger chunkSize;

  if (_subscribedCentral != nil &&
      [_subscribedCentral.identifier.UUIDString isEqualToString:deviceId] &&
      _serverTx != nil) {
    NSUInteger maximum = MAX((NSUInteger)20, _subscribedCentral.maximumUpdateValueLength);
    chunkSize = MIN(maximum, (NSUInteger)180);
  } else if (_connectedPeripheral != nil &&
             [_connectedPeripheral.identifier.UUIDString isEqualToString:deviceId] &&
             _clientRx != nil) {
    chunkSize = _clientChunkSize > 0 ? _clientChunkSize : 20;
  } else {
    return;
  }

  NSMutableArray<NSData *> *queue = [self queueForDevice:deviceId];
  NSUInteger offset = 0;
  while (offset < framed.length) {
    NSUInteger end = MIN(offset + chunkSize, framed.length);
    [queue addObject:[framed subdataWithRange:NSMakeRange(offset, end - offset)]];
    offset = end;
  }
  [self sendNextChunkForDevice:deviceId];
}

- (void)sendNextChunkForDevice:(NSString *)deviceId
{
  if (_subscribedCentral != nil &&
      [_subscribedCentral.identifier.UUIDString isEqualToString:deviceId] &&
      _serverTx != nil) {
    NSMutableArray<NSData *> *queue = [self queueForDevice:deviceId];
    if (queue.count == 0) {
      return;
    }
    NSData *chunk = queue.firstObject;
    BOOL sent = [_peripheralManager updateValue:chunk
                              forCharacteristic:_serverTx
                           onSubscribedCentrals:@[ _subscribedCentral ]];
    if (sent) {
      [queue removeObjectAtIndex:0];
      __weak BluetoothScanner *weakSelf = self;
      dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(0.03 * NSEC_PER_SEC)),
                     dispatch_get_main_queue(), ^{
                       [weakSelf sendNextChunkForDevice:deviceId];
                     });
    }
    return;
  }

  if (_connectedPeripheral != nil &&
      [_connectedPeripheral.identifier.UUIDString isEqualToString:deviceId] &&
      _clientRx != nil) {
    if ([_pendingWrites containsObject:deviceId]) {
      return;
    }
    NSMutableArray<NSData *> *queue = [self queueForDevice:deviceId];
    if (queue.count == 0) {
      return;
    }
    NSData *chunk = queue.firstObject;
    [queue removeObjectAtIndex:0];
    [_pendingWrites addObject:deviceId];
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
  NSMutableData *incomingBuffer = [self incomingBufferForDevice:deviceId];
  [incomingBuffer appendData:data];
  const uint8_t *bytes = (const uint8_t *)incomingBuffer.bytes;
  NSUInteger total = incomingBuffer.length;
  NSUInteger offset = 0;

  while (total - offset >= 4) {
    uint32_t length = ((uint32_t)bytes[offset] << 24) |
                      ((uint32_t)bytes[offset + 1] << 16) |
                      ((uint32_t)bytes[offset + 2] << 8) |
                      (uint32_t)bytes[offset + 3];
    if (length == 0 || length > 1000000) {
      [incomingBuffer setLength:0];
      return;
    }
    if (total - offset - 4 < length) {
      break;
    }
    NSData *payload = [incomingBuffer subdataWithRange:NSMakeRange(offset + 4, length)];
    offset += 4 + length;
    [self dispatchPayload:payload deviceId:deviceId];
  }

  if (offset > 0) {
    [incomingBuffer replaceBytesInRange:NSMakeRange(0, offset) withBytes:NULL length:0];
  }
}

- (void)dispatchPayload:(NSData *)payload deviceId:(NSString *)deviceId
{
  const uint8_t *bytes = (const uint8_t *)payload.bytes;
  NSUInteger total = payload.length;
  if (total < 1) {
    return;
  }

  uint8_t type = bytes[0];
  NSUInteger offset = 1;

  NSString *messageId = [self readStringFrom:bytes length:total offset:&offset];
  if (messageId == nil || total - offset < 8) {
    return;
  }
  uint64_t rawTimestamp = 0;
  for (int index = 0; index < 8; index++) {
    rawTimestamp = (rawTimestamp << 8) | bytes[offset + index];
  }
  offset += 8;
  NSString *resolvedDeviceId = deviceId ?: @"";

  if (type == 1) {
    NSString *text = [self readStringFrom:bytes length:total offset:&offset];
    if (text == nil) {
      return;
    }
    [self emitOnMessageReceived:@{
      @"deviceId" : resolvedDeviceId,
      @"messageId" : messageId,
      @"text" : text,
      @"timestamp" : @((double)rawTimestamp),
    }];
    double now = (double)((uint64_t)([[NSDate date] timeIntervalSince1970] * 1000.0));
    NSData *ack = [self framePayload:[self encodeAckPacket:messageId timestamp:now]];
    [self enqueueFramed:ack forDevice:resolvedDeviceId];
    return;
  }

  if (type == 2) {
    [self emitOnAckReceived:@{
      @"deviceId" : resolvedDeviceId,
      @"messageId" : messageId,
      @"timestamp" : @((double)rawTimestamp),
    }];
  }
}

- (NSString *)readStringFrom:(const uint8_t *)bytes
                      length:(NSUInteger)total
                      offset:(NSUInteger *)offset
{
  if (total - *offset < 2) {
    return nil;
  }
  uint16_t length = ((uint16_t)bytes[*offset] << 8) | (uint16_t)bytes[*offset + 1];
  *offset += 2;
  if (total - *offset < length) {
    return nil;
  }
  NSString *value = [[NSString alloc] initWithBytes:bytes + *offset
                                             length:length
                                           encoding:NSUTF8StringEncoding];
  *offset += length;
  return value;
}

#pragma mark - Client (central role)

- (void)teardownClient
{
  if (_connectedPeripheral != nil) {
    NSString *deviceId = _connectedPeripheral.identifier.UUIDString;
    _connectedPeripheral.delegate = nil;
    if (_centralManager != nil) {
      [_centralManager cancelPeripheralConnection:_connectedPeripheral];
    }
    _connectedPeripheral = nil;
    [_pendingWrites removeObject:deviceId];
    [_outgoingQueues removeObjectForKey:deviceId];
    [_incomingBuffers removeObjectForKey:deviceId];
  }
  _clientRx = nil;
  _clientTx = nil;
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
    NSString *deviceId = peripheral.identifier.UUIDString;
    _connectedPeripheral.delegate = nil;
    _connectedPeripheral = nil;
    _clientRx = nil;
    _clientTx = nil;
    [_pendingWrites removeObject:deviceId];
    [_outgoingQueues removeObjectForKey:deviceId];
    [_incomingBuffers removeObjectForKey:deviceId];
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
  NSString *deviceId = peripheral.identifier.UUIDString;
  [_pendingWrites removeObject:deviceId];
  [self sendNextChunkForDevice:deviceId];
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
    NSString *deviceId = central.identifier.UUIDString;
    _subscribedCentral = nil;
    [_pendingWrites removeObject:deviceId];
    [_outgoingQueues removeObjectForKey:deviceId];
    [_incomingBuffers removeObjectForKey:deviceId];
    [self emitOnConnectionStateChanged:@{
      @"connected" : @(NO),
      @"deviceId" : deviceId,
    }];
  }
}

- (void)peripheralManagerIsReadyToUpdateSubscribers:(CBPeripheralManager *)peripheral
{
  if (_subscribedCentral != nil) {
    [self sendNextChunkForDevice:_subscribedCentral.identifier.UUIDString];
  }
}

@end
