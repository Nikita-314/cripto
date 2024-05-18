import 'package:cripto_coins/features/cripto_list/bloc/crypto_list_bloc.dart';
import 'package:cripto_coins/features/cripto_list/widgets/widgets.dart';
import 'package:flutter/material.dart';
import 'package:get_it/get_it.dart';
import '../../../repositories/crypto_coins/crypto_coins.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

class CryptoListScreen extends StatefulWidget {
  const CryptoListScreen({
    super.key,
  });

  @override
  State<CryptoListScreen> createState() => _CryptoListScreenState();
}

class _CryptoListScreenState extends State<CryptoListScreen> {
  List<CryptoCoin>? _cryptoCoinsList;

  final _cryptoListBloc = CryptoListBloc(
    GetIt.I<AbstractCoinsRepository>(),
  );

  @override
  void initState() {
    _cryptoListBloc.add(LoadCryptoList());
    super.initState();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text(
          'CriptoCurrenciesList',
        ),
      ),
      body: BlocBuilder<CryptoListBloc, CryptoListState>(
        bloc: _cryptoListBloc,
        builder: (context, state) {
          if (state is CryptoListLoaded) {
            return ListView.separated(
              padding: const EdgeInsets.only(top: 16),
              itemCount: state.coinList.length,
              separatorBuilder: (context, index) => const Divider(),
              itemBuilder: (context, i) {
                final coin = state.coinList[i];
                //final coinName = coin.name;
                return CriptoCoinTile(coin: coin);
              },
            );
          }
          if (state is CryptoListLoadingFailure) {
            return Center(
              child: Text('Something went wrong'),
            );
          }
          return const Center(child: CircularProgressIndicator());
        },
      ),

      // (_cryptoCoinsList == null)
      //     ? const Center(child: CircularProgressIndicator())
      //     //? const SizedBox()
    );
  }
}
