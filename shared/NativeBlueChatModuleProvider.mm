#import <ReactCommon/RCTTurboModule.h>

#import "NativeBlueChatModule.h"

using namespace facebook::react;

@interface NativeBlueChatModuleProvider : NSObject <RCTModuleProvider>
@end

@implementation NativeBlueChatModuleProvider

- (std::shared_ptr<TurboModule>)getTurboModule:(const ObjCTurboModule::InitParams &)params
{
  return std::make_shared<NativeBlueChatModule>(params.jsInvoker);
}

@end
