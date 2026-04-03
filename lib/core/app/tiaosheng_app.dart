import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:tiaosheng/core/i18n/app_i18n.dart';
import 'package:tiaosheng/core/router/app_router.dart';

class TiaoShengApp extends StatelessWidget {
  const TiaoShengApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp.router(
      onGenerateTitle: (context) => context.i18n.appTitle,
      locale: const Locale('zh'),
      localizationsDelegates: const [
        AppI18n.delegate,
        GlobalMaterialLocalizations.delegate,
        GlobalWidgetsLocalizations.delegate,
        GlobalCupertinoLocalizations.delegate,
      ],
      supportedLocales: AppI18n.supportedLocales,
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.blue),
      ),
      routerConfig: appRouter,
    );
  }
}
