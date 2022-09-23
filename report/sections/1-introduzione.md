# Introduzione

`ZIO` è una nuova libreria per la programmazione concorrente che si avvale delle peculiarità del linguaggio di programmazione Scala, al fine di sviluppare applicazioni concorrenti più efficienti, resilienti e facilmente testabili. L'impiego pervasivo di `ZIO` permette ai programmatori di concentrarsi sulla logica di business, favorendo un aumento della produttività e semplificando le avversità derivanti dallo sviluppo delle moderne applicazioni:

- **concorrenza**: `ZIO` è in grado di eseguire in maniera concorrente milioni di _thread_ virtuali tramite un modello asincrono _fiber-based_;
- **efficienza**: `ZIO` cancella automaticamente computazioni il cui risultato non è più necessario;
- **gestione errori**: in `ZIO` vi è una gestione statica degli errori, che abilita il compilatore a segnalare le porzioni di codice fallibili indicando la tipologia di errore;
- ***resource-safety***: `ZIO` gestisce automaticamente il ciclo di vita (acquisizione e rilascio) delle risorse anche in presenza di fallimenti inaspettati e in contesti di concorrenza. Inoltre `ZIO` è _stack-safe_, quindi permette un ampio utilizzo della ricorsione.
- ***streaming***: `ZIO` dispone di uno _streaming_ potente, efficiente e concorrente, in grado di lavorare con qualsiasi sorgente di dati;
- **risoluzione dei problemi**: `ZIO` cattura qualsiasi errore e per ciascuno fornisce un _trace_ di esecuzione utile all'attività di _debugging_;
- **testabilità**: tramite l'_inferenza delle dipendenze_, `ZIO` favorisce lo sviluppo di interfacce la cui concretizzazione è dettata dal contesto di utilizzo.

`ZIO` non è l'unica scelta possibile per la programmazione concorrente in Scala. Infatti tra le principali si ricorda:

- **Akka[^1]**: _toolkit_ maturo che comprende un ricco ecosistema orientato alla produttività. Rispetto a `ZIO` non fornisce lo stesso livello di diagnostica e non prevede una forte analisi degli errori a tempo di compilazione che rendono `ZIO` completamente testabile;
- **Monix[^2]**: libreria per la composizione di programmi asincroni, non è in grado di fornire lo stesso potere espressivo di ZIO;
- **Cats Effects[^3]**: _runtime system_ puramente funzionale. Rispetto a `ZIO` è più focalizzata sul concetto di _tagless-final_ che sulla programmazione concorrente e non prevede un supporto per la composizione delle transazioni.

[^1]: Per dettagli: [https://akka.io/](https://akka.io/)

[^2]: Per dettagli: [https://monix.io/](https://monix.io/)

[^3]: Per dettagli: [https://cats-effect/](https://typelevel.org/cats-effect/)