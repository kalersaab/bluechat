#pragma once

#include <BlueChatSpecsJSI.h>

#include <memory>
#include <string>

namespace facebook::react {

class NativeBlueChatModule
    : public NativeBlueChatModuleCxxSpec<NativeBlueChatModule> {
 public:
  explicit NativeBlueChatModule(std::shared_ptr<CallInvoker> jsInvoker);

  std::string getDeviceId(jsi::Runtime& rt);
  std::string getDeviceName(jsi::Runtime& rt);
  std::string getDeviceType(jsi::Runtime& rt);
  std::string getDeviceOS(jsi::Runtime& rt);
  std::string getDeviceOSVersion(jsi::Runtime& rt);
  std::string getDeviceOSBuild(jsi::Runtime& rt);
  std::string getDeviceOSBuildVersion(jsi::Runtime& rt);
};

} // namespace facebook::react
