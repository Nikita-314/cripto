import 'package:flutter/material.dart';

final darkTheme = ThemeData(
  scaffoldBackgroundColor: Color.fromARGB(255, 53, 52, 52),
  primarySwatch: Colors.yellow,
  //colorScheme: ColorScheme.fromSeed(seedColor: Colors.yellow),
  dividerColor: Colors.white24,
  appBarTheme: const AppBarTheme(
    iconTheme: IconThemeData(color: Colors.white),
    elevation: 0,
    backgroundColor: Color.fromARGB(255, 56, 53, 53),
  ),
  listTileTheme: ListTileThemeData(iconColor: Colors.white),
  useMaterial3: true,
  textTheme: TextTheme(
      bodyMedium: const TextStyle(
        color: Colors.white,
        fontWeight: FontWeight.w500,
        fontSize: 20,
      ),
      labelSmall: TextStyle(
        color: Colors.white.withOpacity(0.6),
        fontWeight: FontWeight.w700,
        fontSize: 14,
      )),
);
