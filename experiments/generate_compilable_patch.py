from openai import OpenAI
from transformers import AutoTokenizer, AutoModelForCausalLM
import torch
import sys
import os

def extract_compilable_patch(response_text):
    """Extract the Java content between ```java and the next ```."""
    start_marker = "```java"
    end_marker = "```"

    start_index = response_text.find(start_marker)
    end_index = response_text.find(end_marker, start_index + len(start_marker))

    if start_index != -1 and end_index != -1:
        return response_text[start_index + len(start_marker):end_index].strip()
    else:
        print("No Java content found.")
        return ""

def generate_compilable_patch(model, client, class_name, java_file_path, patch_content):
    patch_file = os.path.join(os.getcwd(), "patch.txt")
    with open(patch_file, 'r') as file:
        patch_content = file.read()
    
    try:
        with open(java_file_path, 'r') as java_file:
            java_content = java_file.read()
    except FileNotFoundError:
        print(f"Java file not found at {java_file_path}")
        return

    prompt = f"Given the code for the original test class {class_name}: \n\n```\n{java_content}\n```, generate an updated version of the class code that addresses changes in the following patch you suggested earlier:\n\n```\n{patch_content}\n```" +\
            "Existing classes, fields and methods, if not covered by the patch, shall be included directly without changes. Please ensure you leave all code not to be changed by the patch as-is. Please do not remove or omit existing code" +\
            f"Notice that the patch only deals with one test, so your generated class shall only change that method and/or the relevant helper method (if they're changed as per the patch)." +\
            "Also, please ensure including all package declaration and import statements in the original test class. Do not change or remove other tests. Include all tests not changed as well - it shall contain all tests in the original code." +\
            "Your code shall be directly compilable when substituting the original test file as a whole. Once again, remember to directly apply the changes suggested by the patch and do NOT make unnecessary changes, and do not omit unchanged code." +\
            "Wrap your patched test class code in a Java code block starting with '```java' and ending with '```'" 
    print(prompt)
    
    if model.startswith('GPT'):
        GPTModel = "gpt-4"
        if model == 'GPT3.5':
            GPTModel = "gpt-3.5-turbo"
        message=[{"role": "user", "content": prompt}]
        response = client.chat.completions.create(
                    model=GPTModel,
                    messages=message,
                    temperature=0.2,
                    max_tokens=1024)
        compilable_patch_content = response.choices[0].message.content.strip()
    elif model == 'DeepSeek':
        tokenizer = AutoTokenizer.from_pretrained("deepseek-ai/deepseek-coder-33b-instruct", trust_remote_code=True)
        model = AutoModelForCausalLM.from_pretrained("deepseek-ai/deepseek-coder-33b-instruct", trust_remote_code=True, torch_dtype=torch.bfloat16).cuda()
        messages = [
            { 'role': 'user', 'content': prompt}
        ]
        inputs = tokenizer.apply_chat_template(messages, add_generation_prompt=True, return_tensors="pt").to(model.device)
        outputs = model.generate(inputs, max_new_tokens=1024, do_sample=True, temperature=0.2, num_return_sequences=1, eos_token_id=tokenizer.eos_token_id)
        compilable_patch_content = tokenizer.decode(outputs[0][len(inputs[0]):], skip_special_tokens=True).strip()
    elif model == 'Qwen':
        model = AutoModelForCausalLM.from_pretrained(
            "Qwen/Qwen2.5-Coder-32B-Instruct",
            torch_dtype="auto",
            device_map="auto"
        ).cuda()
        tokenizer = AutoTokenizer.from_pretrained("Qwen/Qwen2.5-Coder-32B-Instruct")
        messages = [
            {"role": "system", "content": "You are Qwen, created by Alibaba Cloud. You are a helpful assistant."},
            {"role": "user", "content": prompt}
        ]
        text = tokenizer.apply_chat_template(
            messages,
            tokenize=False,
            add_generation_prompt=True
        )
        model_inputs = tokenizer([text], return_tensors="pt").to(model.device)
        generated_ids = model.generate(
            **model_inputs,
            max_new_tokens=1024,
            do_sample=True,
            temperature=0.6
        )
        generated_ids = [
            output_ids[len(input_ids):] for input_ids, output_ids in zip(model_inputs.input_ids, generated_ids)
        ]
        compilable_patch_content = tokenizer.batch_decode(generated_ids, skip_special_tokens=True)[0]
    else:
        raise Exception("Unsupported Model.")
    print(compilable_patch_content)
    compilable_patch_content = extract_compilable_patch(compilable_patch_content)
    
    compilable_patch_file_path = f"{class_name}.java"
    with open(compilable_patch_file_path, 'w') as compilable_patch_file:
        compilable_patch_file.write(compilable_patch_content)

    print(f"compilable_patch file created: {compilable_patch_file_path}")

if __name__ == "__main__":
    if len(sys.argv) < 4:
        print("Missing parameters. Usage: python3 generate_compilable_patch.py <class_name> <java_file_path> <model> <optional_API_key>")
        sys.exit(1)

    model = sys.argv[3]
    client = None
    if model == 'GPT4' or model == 'GPT3.5':
        if len(sys.argv) < 5:
            print("Error: Missing parameters. Please specify your API key")
            sys.exit(1)
        elif len(sys.argv) > 5:
            print("Error: Too many parameters")
            sys.exit(1)
        client = OpenAI(api_key=sys.argv[4])
    elif model == 'DeepSeek' or model == 'Qwen':
        if len(sys.argv) > 4:
            print("Error: Too many parameters")
            sys.exit(1)
    else:
        print("Error: Unsupported Model")
        sys.exit(1)

    class_name = sys.argv[1]
    java_file_path = sys.argv[2]

    patch_content = ""
    with open('patch.txt', 'r') as patch_file:
        patch_content = patch_file.read()

    generate_compilable_patch(model, client, class_name, java_file_path, patch_content)
