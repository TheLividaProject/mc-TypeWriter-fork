import 'package:flutter/material.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:typewriter/models/adapter.dart';
import 'package:typewriter/widgets/inspector/editors.dart';

import '../../inspector.dart';

class BooleanEditorFilter extends EditorFilter {
  @override
  bool canFilter(FieldType type) =>
      type is PrimitiveField && type.type == PrimitiveFieldType.boolean;

  @override
  Widget build(String path, FieldType type) =>
      BooleanEditor(path: path, field: type as PrimitiveField);
}

class BooleanEditor extends HookConsumerWidget {
  final String path;
  final PrimitiveField field;

  const BooleanEditor({
    super.key,
    required this.path,
    required this.field,
  }) : super();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final value = ref.watch(fieldValueProvider(path, false));
    return Row(
      children: [
        SelectableText(ref.watch(pathDisplayNameProvider(path))),
        Checkbox(
            value: value,
            onChanged: (value) {
              ref
                  .read(entryDefinitionProvider)
                  ?.updateField(ref, path, value ?? false);
            }),
        if (value)
          const Text("True", style: TextStyle(color: Colors.greenAccent))
        else
          const Text("False", style: TextStyle(color: Colors.grey)),
      ],
    );
  }
}
