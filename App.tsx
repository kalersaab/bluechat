import { StatusBar } from 'react-native';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import MessageHandler from './components/MessageHandler';
import RootNavigator from './navigation/RootNavigator';

function App() {
  return (
    <SafeAreaProvider>
      <StatusBar barStyle="light-content" />
      <MessageHandler />
      <RootNavigator />
    </SafeAreaProvider>
  );
}

export default App;
