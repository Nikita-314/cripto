import 'package:bloc/bloc.dart';
import 'package:cripto_coins/repositories/crypto_coins/abstract_coins_repository.dart';
import 'package:cripto_coins/repositories/crypto_coins/models/crypto_coin.dart';

part 'crypto_list_event.dart';
part 'crypto_list_state.dart';

class CryptoListBloc extends Bloc<CryptoListEvent, CryptoListState> {
  CryptoListBloc(this.coinsRepository) : super(CryptoListInitial()) {
    on<LoadCryptoList>((event, emit) async {
      //логика загрузки данных
      try {
        final coinList = await coinsRepository.getCoinsList();
        emit(CryptoListLoaded(coinList: coinList));
      } catch (e) {
        emit(CryptoListLoadingFailure(exception: e));
      }
    });
  }

  final AbstractCoinsRepository coinsRepository;
}
