import 'package:cripto_coins/features/cripto_coin/view/cripto_coin_screen.dart';
import 'package:cripto_coins/features/cripto_list/view/cripto_list_screen.dart';

final routes = {
  '/': (context) => const CryptoListScreen(),
  '/coin': (context) => const CryptoCoinScreen(),
};
