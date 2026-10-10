#include "NativeBlueChatModule.h"

#include <utility>

namespace facebook::react {

NativeBlueChatModule::NativeBlueChatModule(
    std::shared_ptr<CallInvoker> jsInvoker)
    : NativeBlueChatModuleCxxSpec(std::move(jsInvoker)) {}

std::string NativeBlueChatModule::getDeviceId(jsi::Runtime& rt) {
  return "1234567890";
}

std::string NativeBlueChatModule::getDeviceName(jsi::Runtime& rt) {
  return "Device Name";
}

std::string NativeBlueChatModule::getDeviceType(jsi::Runtime& rt) {
  return "Device Type";
}

std::string NativeBlueChatModule::getDeviceOS(jsi::Runtime& rt) {
  return "Device OS";
}

std::string NativeBlueChatModule::getDeviceOSVersion(jsi::Runtime& rt) {
  return "Device OS Version";
}

std::string NativeBlueChatModule::getDeviceOSBuild(jsi::Runtime& rt) {
  return "Device OS Build";
}

std::string NativeBlueChatModule::getDeviceOSBuildVersion(jsi::Runtime& rt) {
  return "Device OS Build Version";
}

} // namespace facebook::react
