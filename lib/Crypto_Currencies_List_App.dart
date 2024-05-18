import 'package:cripto_coins/router/router.dart';
import 'package:cripto_coins/them/them.dart';
import 'package:flutter/material.dart';

class CryptoCurrenciesListApp extends StatelessWidget {
  const CryptoCurrenciesListApp({super.key});
  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Crypto Currencies List',
      theme: darkTheme,
      routes: routes,
      //home: const CryptoListScreen(title: 'Crypto Currencies List'),
    );
  }
}
