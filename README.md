# ML-anticheat-linker
Java-плагин для связки античитов на машинном обучении, с помощью которого античиты могут корелировать и выдавать общее наказание после консенсуса, исключая ложные баны.


- ENGLISH
## Third-party components (optional)
This repository contains only the author's code: linker, consensus logic, custom math detector, admin tooling, persistence.

The system works standalone. Three ML anti-cheat modules are optional third-party integrations:
- they are NOT included in this repository;
- they are connected via a shared interface;
- with them, detection quality improves significantly (lower false negatives), but the core works without them.

## About plugin.yml file

Its NOT recommended to change settings too far, if you're not afraid. DO NOT change VL to value below 0.8 as there can be lots of false-positive bans, and also I don't recommend to set opinions value to 1. From a start, the value is 2.

- RUSSIAN
## Cторонние компоненты (необязательно) 
В данном репозитории лежит ТОЛЬКО мой плагин-линкер. Связующий математический античит моей разработки не лежит.
Система работает в одиночку, но три других ML-античита нужны для полноценной и идеальной работы из коробки.

## Про plugin.yml
Нежелательно менять параметры, если вы не уверены. Крайне не рекомендуется понижать степень мнений античитов ниже порога VL = 0.8, так как может быть слишком много ложных банов, а также не рекомендую ставить значение необходимых мнений на 1. По умолчанию установлено значение 2.

