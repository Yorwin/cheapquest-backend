---
description: Crea un commit contextual basado en los cambios y la conversación
agent: build
---

Crea un commit de los cambios actuales. NO ejecutes push.

Contexto adicional que el usuario quiere añadir al mensaje:
$ARGUMENTS

Estado de los cambios:
!`git status --short`

Diff staged + unstaged:
!`git diff --HEAD`

Pasos:
1. Analiza el diff anterior Y el contexto de nuestra conversación (qué pidió el usuario, qué plan seguimos, qué archivos tocamos y por qué) para entender la intención real del cambio.
2. Si $ARGUMENTS trae información útil (nº de ticket, WIP, nota para reviewers, scope concreto, etc.), intégrala de forma natural en el mensaje.
3. Redacta el mensaje en Conventional Commits (feat, fix, chore, docs, refactor, test, perf, build, ci) con scope opcional entre paréntesis. Imperativo presente, primera línea ≤ 72 caracteres. Cuerpo con viñetas solo si aporta "por qué".
4. Si no hay nada que commitear (working tree limpio), avisa y detente sin hacer nada.
5. Ejecuta directamente, sin pedir confirmación: `git add -A && git commit -m "<mensaje>"`.
6. Muestra al final el `git log -1 --stat` para confirmar.
